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
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.internal.storage.DirectoryBasedStorageManager;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryManager;

public class GitRepositoryManager extends DirectoryBasedStorageManager<Repository>
                                  implements RepositoryManager {

    private static final Logger logger = LoggerFactory.getLogger(GitRepositoryManager.class);

    private final Project parent;
    private final GitRepositoryFormat preferredFormat;
    private final Executor repositoryWorker;
    private final RepositoryCache cache;

    public GitRepositoryManager(Project parent, File rootDir, Executor repositoryWorker,
                                @Nullable RepositoryCache cache) {
        this(parent, rootDir, GitRepositoryFormat.V1, repositoryWorker, cache);
    }

    public GitRepositoryManager(Project parent, File rootDir, GitRepositoryFormat preferredFormat,
                                Executor repositoryWorker, @Nullable RepositoryCache cache) {

        super(rootDir, Repository.class);
        this.parent = requireNonNull(parent, "parent");
        this.preferredFormat = requireNonNull(preferredFormat, "preferredFormat");
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
        this.cache = cache;
        init();
    }

    @Override
    public Project parent() {
        return parent;
    }

    @Override
    protected Repository openChild(File childDir) throws Exception {
        final GitRepository repository = new GitRepository(parent, childDir, repositoryWorker, cache);
        if (repository.needsMigration(preferredFormat)) {
            return migrate(childDir, parent, repositoryWorker, repository, preferredFormat, cache);
        } else {
            return repository;
        }
    }

    private static Repository migrate(File childDir, Project project, Executor repositoryWorker,
                                      GitRepository oldRepo, GitRepositoryFormat newFormat,
                                      RepositoryCache cache) throws IOException {
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
            oldRepo.internalClose();
            closedOldRepo = true;

            if (!childDir.renameTo(oldChildDir)) {
                throw new IOException("failed to rename " + childDir + " to " + oldChildDir);
            }
            if (!newChildDir.renameTo(childDir)) {
                throw new IOException("failed to rename " + newChildDir + " to " + childDir);
            }

            final GitRepository newRepo = new GitRepository(project, childDir, repositoryWorker, cache);
            logger.info("Migrated from {} to {}: {}", oldRepo.format(), newFormat, newRepo);
            return newRepo;
        } finally {
            if (!closedOldRepo) {
                oldRepo.internalClose();
            }
        }
    }

    private static void deleteCruft(File dir) throws IOException {
        logger.info("Deleting the cruft from previous migration: {}", dir);
        Util.deleteFileTree(dir);
        logger.info("Deleted the cruft from previous migration: {}", dir);
    }

    @Override
    protected Repository createChild(File childDir, Author author, long creationTimeMillis) throws Exception {
        return new GitRepository(parent, childDir, preferredFormat, repositoryWorker,
                                 creationTimeMillis, author, cache);
    }

    @Override
    protected void closeChild(File childDir, Repository child,
                              Supplier<CentralDogmaException> failureCauseSupplier) {
        ((GitRepository) child).close(failureCauseSupplier);
    }

    @Override
    protected CentralDogmaException newStorageExistsException(String name) {
        return new RepositoryExistsException(parent().name() + '/' + name);
    }

    @Override
    protected CentralDogmaException newStorageNotFoundException(String name) {
        return new RepositoryNotFoundException(parent().name() + '/' + name);
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
