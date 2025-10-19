/*
 * Copyright 2019 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.centraldogma.server.internal.storage;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.server.internal.ExecutorServiceUtil.terminate;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.Tokens;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

/**
 * A service class for purging projects and repositories that were removed before.
 */
public class PurgeSchedulingService {

    private static final Logger logger = LoggerFactory.getLogger(PurgeSchedulingService.class);

    /**
     * How often to run the storage purge schedules. i.e. every minute.
     */
    private static final Duration TICK = Duration.ofMinutes(1);

    private final ProjectManager projectManager;
    private final ScheduledExecutorService purgeWorker;
    private final long maxRemovedRepositoryAgeMillis;
    private final StoragePurgingScheduler storagePurgingScheduler = new StoragePurgingScheduler();

    public PurgeSchedulingService(ProjectManager projectManager, ScheduledExecutorService purgeWorker,
                                  long maxRemovedRepositoryAgeMillis) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.purgeWorker = requireNonNull(purgeWorker, "purgeWorker");
        checkArgument(maxRemovedRepositoryAgeMillis >= 0,
                      "maxRemovedRepositoryAgeMillis: %s (expected: >= 0)", maxRemovedRepositoryAgeMillis);
        this.maxRemovedRepositoryAgeMillis = maxRemovedRepositoryAgeMillis;
    }

    public void start(CommandExecutor commandExecutor,
                      MetadataService metadataService) {
        if (isDisabled()) {
            return;
        }
        requireNonNull(commandExecutor, "commandExecutor");
        requireNonNull(metadataService, "metadataService");
        cleanPurgedFiles();
        storagePurgingScheduler.start(() -> {
            try {
                purgeProjectAndRepository(commandExecutor, metadataService);
                purgeToken(metadataService);
            } catch (Exception e) {
                logger.warn("Unexpected purging service failure", e);
            }
        });
    }

    public boolean isStarted() {
        return storagePurgingScheduler.isStarted();
    }

    public void stop() {
        if (!isDisabled()) {
            storagePurgingScheduler.stop();
        }
    }

    private void cleanPurgedFiles() {
        projectManager.purgeMarked();
        projectManager.list().forEach((unused, project) -> project.repos().purgeMarked());
    }

    @VisibleForTesting
    void purgeProjectAndRepository(CommandExecutor commandExecutor,
                                   MetadataService metadataService) {
        final long minAllowedTimestamp = System.currentTimeMillis() - maxRemovedRepositoryAgeMillis;
        final Predicate<Instant> olderThanMinAllowed =
                removedAt -> removedAt.toEpochMilli() < minAllowedTimestamp;

        purgeProject(commandExecutor, olderThanMinAllowed);
        purgeRepository(commandExecutor, metadataService, olderThanMinAllowed);
    }

    private void purgeProject(CommandExecutor commandExecutor,
                              Predicate<Instant> olderThanMinAllowed) {
        projectManager
                .listRemoved()
                .forEach((projectName, removal) -> {
                    if (olderThanMinAllowed.test(removal)) {
                        commandExecutor
                                .execute(Command.purgeProject(Author.SYSTEM, projectName)).join();
                    }
                });
    }

    private void purgeRepository(CommandExecutor commandExecutor,
                                 MetadataService metadataService,
                                 Predicate<Instant> olderThanMinAllowed) {
        projectManager
                .list()
                .forEach((unused, project) -> {
                    project.repos().listRemoved()
                           .forEach((repoName, removal) -> {
                               if (olderThanMinAllowed.test(removal)) {
                                   commandExecutor.execute(Command.purgeRepository(Author.SYSTEM,
                                                                                   project.name(),
                                                                                   repoName))
                                                  .join();
                                   metadataService.purgeRepo(Author.SYSTEM, project.name(), repoName).join();
                               }
                           });
                });
    }

    private static void purgeToken(MetadataService metadataService) {
        final Tokens tokens = metadataService.getTokens();
        final List<String> purging = tokens.appIds().values()
                                           .stream()
                                           .filter(Token::isDeleted)
                                           .map(Token::appId)
                                           .collect(toImmutableList());

        if (!purging.isEmpty()) {
            logger.info("Purging {} tokens: {}", purging.size(), purging);
            purging.forEach(appId -> metadataService.purgeToken(Author.SYSTEM, appId));
        }
    }

    private boolean isDisabled() {
        return maxRemovedRepositoryAgeMillis == 0;
    }

    private final class StoragePurgingScheduler {

        @Nullable
        private volatile ListeningScheduledExecutorService scheduler;

        public boolean isStarted() {
            return scheduler != null;
        }

        public synchronized void start(Runnable task) {
            if (isStarted()) {
                return;
            }
            requireNonNull(task, "task");
            final ListeningScheduledExecutorService scheduler = MoreExecutors.listeningDecorator(purgeWorker);
            this.scheduler = scheduler;
            final ListenableScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                    task,
                    TICK.getSeconds(), TICK.getSeconds(), TimeUnit.SECONDS);

            Futures.addCallback(future, new FutureCallback<Object>() {
                @Override
                public void onSuccess(@Nullable Object result) {}

                @Override
                public void onFailure(Throwable cause) {
                    logger.error("Storage purge scheduler stopped due to an unexpected exception:", cause);
                }
            }, purgeWorker);
        }

        public synchronized void stop() {
            final ExecutorService scheduler = this.scheduler;

            try {
                final boolean interrupted = terminate(scheduler);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                this.scheduler = null;
            }
        }
    }
}
