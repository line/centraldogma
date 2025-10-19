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

package com.linecorp.centraldogma.server.internal.storage.project;

import static com.linecorp.centraldogma.internal.Util.deleteFileTree;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryManager.isEncryptedRepository;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryManager.removeInterfixAndPurgedSuffix;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.DirectoryBasedStorageManager;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.MeterRegistry;

public final class DefaultProjectManager extends DirectoryBasedStorageManager<Project>
        implements ProjectManager {

    private static final Logger logger = LoggerFactory.getLogger(DefaultProjectManager.class);

    private final Executor repositoryWorker;
    @Nullable
    private final RepositoryCache cache;

    public DefaultProjectManager(File rootDir, Executor repositoryWorker, Executor purgeWorker,
                                 MeterRegistry meterRegistry, @Nullable String cacheSpec,
                                 EncryptionStorageManager encryptionStorageManager) {
        super(rootDir, Project.class, purgeWorker, encryptionStorageManager);

        requireNonNull(meterRegistry, "meterRegistry");
        requireNonNull(repositoryWorker, "repositoryWorker");

        this.repositoryWorker = repositoryWorker;
        cache = cacheSpec != null ? new RepositoryCache(cacheSpec, meterRegistry) : null;

        init();
    }

    @Override
    public void close(Supplier<CentralDogmaException> failureCauseSupplier) {
        super.close(failureCauseSupplier);
        if (cache != null) {
            cache.clear();
        }
    }

    @Override
    protected Project openChild(File childDir) throws Exception {
        return new DefaultProject(childDir, repositoryWorker, purgeWorker(), cache, encryptionStorageManager());
    }

    @Override
    protected Project createChild(
            File childDir, Author author, long creationTimeMillis, boolean encrypt) throws Exception {
        final Project dogmaProject;
        if (exists(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA)) {
            dogmaProject = get(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA);
        } else {
            dogmaProject = null;
        }
        return new DefaultProject(dogmaProject, childDir, repositoryWorker, purgeWorker(),
                                  creationTimeMillis, author, cache, encryptionStorageManager(), encrypt);
    }

    @Override
    protected void closeChild(File childDir, Project child,
                              Supplier<CentralDogmaException> failureCauseSupplier) {
        final DefaultProject c = (DefaultProject) child;
        c.repos.close(failureCauseSupplier);
    }

    @Override
    protected CentralDogmaException newStorageExistsException(String name) {
        return ProjectExistsException.of(name);
    }

    @Override
    protected CentralDogmaException newStorageNotFoundException(String name) {
        return ProjectNotFoundException.of(name);
    }

    @Override
    protected void deletePurged(File file) {
        final String projectName = removeInterfixAndPurgedSuffix(file.getName());
        logger.info("Deleting a purged project: {} ..", projectName);
        try {
            final File[] childFiles = file.listFiles();
            if (childFiles != null) {
                for (File child : childFiles) {
                    if (child.isDirectory() && isEncryptedRepository(child)) {
                        final String name = child.getName();
                        final String repoName;
                        if (name.endsWith(SUFFIX_REMOVED))  {
                            repoName = name.substring(0, name.length() - SUFFIX_REMOVED.length());
                        } else if (name.endsWith(SUFFIX_PURGED)) {
                            repoName = removeInterfixAndPurgedSuffix(name);
                        } else {
                            repoName = name;
                        }
                        encryptionStorageManager().deleteRepositoryData(projectName, repoName);
                    }
                }
            }
            deleteFileTree(file);
            logger.info("Deleted a purged project: {}.", projectName);
        } catch (IOException e) {
            logger.warn("Failed to delete a purged project: {}", projectName, e);
        }
    }
}
