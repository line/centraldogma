/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.internal.storage.DirectoryBasedStorageManager;
import com.linecorp.centraldogma.server.internal.storage.StorageExistsException;
import com.linecorp.centraldogma.server.internal.storage.StorageNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryExistsException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryNotFoundException;

public class GitRepositoryManager extends DirectoryBasedStorageManager<Repository>
                                  implements RepositoryManager {

    private static final Logger logger = LoggerFactory.getLogger(GitRepositoryManager.class);

    public GitRepositoryManager(Project parent, File rootDir, Executor repositoryWorker) {
        this(parent, rootDir, GitRepositoryFormat.V1, repositoryWorker);
    }

    public GitRepositoryManager(Project parent, File rootDir, GitRepositoryFormat preferredFormat,
                                Executor repositoryWorker) {

        super(rootDir, Repository.class,
              requireNonNull(parent, "parent"),
              requireNonNull(preferredFormat, "preferredFormat"),
              requireNonNull(repositoryWorker, "repositoryWorker"));
    }

    @Override
    protected Repository openChild(File childDir, Object[] childArgs) throws Exception {
        final Project project = (Project) childArgs[0];
        final GitRepositoryFormat preferredFormat = (GitRepositoryFormat) childArgs[1];
        final Executor repositoryWorker = (Executor) childArgs[2];
        final GitRepository repository = new GitRepository(project, childDir, repositoryWorker);
        if (repository.needsMigration(preferredFormat)) {
            return migrate(childDir, project, repositoryWorker, repository, preferredFormat);
        } else {
            return repository;
        }
    }

    private static Repository migrate(File childDir, Project project, Executor repositoryWorker,
                                      GitRepository oldRepo, GitRepositoryFormat newFormat) throws IOException {
        boolean closedOldRepo = false;
        try {
            logger.info("Migrating from {} to {}: {}", oldRepo.format(), newFormat, oldRepo);
            final File newChildDir = new File(childDir.getParentFile(),
                                              "_newfmt_" + childDir.getName());
            final File oldChildDir = new File(childDir.getParentFile(),
                                              "_oldfmt_" + childDir.getName());

            if (newChildDir.exists()) {
                deleteCruft(newChildDir);
            }
            if (oldChildDir.exists()) {
                deleteCruft(oldChildDir);
            }

            oldRepo.cloneTo(newChildDir, newFormat, new MigrationProgressLogger(oldRepo));
            oldRepo.close();
            closedOldRepo = true;

            if (!childDir.renameTo(oldChildDir)) {
                throw new IOException("failed to rename " + childDir + " to " + oldChildDir);
            }
            if (!newChildDir.renameTo(childDir)) {
                throw new IOException("failed to rename " + newChildDir + " to " + childDir);
            }

            final GitRepository newRepo = new GitRepository(project, childDir, repositoryWorker);
            logger.info("Migrated from {} to {}: {}", oldRepo.format(), newFormat, newRepo);
            return newRepo;
        } finally {
            if (!closedOldRepo) {
                oldRepo.close();
            }
        }
    }

    private static void deleteCruft(File dir) throws IOException {
        logger.info("Deleting the cruft from previous migration: {}", dir);
        Util.deleteFileTree(dir);
        logger.info("Deleted the cruft from previous migration: {}", dir);
    }

    @Override
    protected Repository createChild(File childDir, Object[] childArgs,
                                     long creationTimeMillis) throws Exception {

        final Project project = (Project) childArgs[0];
        final GitRepositoryFormat preferredFormat = (GitRepositoryFormat) childArgs[1];
        final Executor repositoryWorker = (Executor) childArgs[2];
        return new GitRepository(project, childDir, preferredFormat, repositoryWorker,
                                 creationTimeMillis, Author.SYSTEM);
    }

    @Override
    protected void closeChild(File childDir, Repository child) {
        ((GitRepository) child).close();
    }

    @Override
    protected StorageExistsException newStorageExistsException(String name) {
        return new RepositoryExistsException(name);
    }

    @Override
    protected StorageNotFoundException newStorageNotFoundException(String name) {
        return new RepositoryNotFoundException(name);
    }

    /**
     * Logs the migration progress periodically.
     */
    private static class MigrationProgressLogger implements BiConsumer<Integer, Integer> {

        private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

        private final String name;
        private final long startTimeNanos;
        private long lastReportTimeNanos;

        MigrationProgressLogger(Repository repo) {
            name = repo.parent().name() + '/' + repo.name();
            startTimeNanos = lastReportTimeNanos = System.nanoTime();
        }

        @Override
        public void accept(Integer current, Integer total) {
            final long currentTimeNanos = System.nanoTime();
            final long elapsedTimeNanos = currentTimeNanos - startTimeNanos;
            if (currentTimeNanos - lastReportTimeNanos > REPORT_INTERVAL_NANOS) {
                logger.info("{}: {}% ({}/{}) - took {}",
                            name, (int) ((double) current / total * 100),
                            current, total, TextFormatter.elapsed(elapsedTimeNanos));
                lastReportTimeNanos = currentTimeNanos;
            } else if (current.equals(total)) {
                logger.info("{}: 100% ({}/{}) - took {}",
                            name, current, total,
                            TextFormatter.elapsed(elapsedTimeNanos));
            }
        }
    }
}
