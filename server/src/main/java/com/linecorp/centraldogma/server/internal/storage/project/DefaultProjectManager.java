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

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.DirectoryBasedStorageManager;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.MeterRegistry;

public class DefaultProjectManager extends DirectoryBasedStorageManager<Project> implements ProjectManager {

    private final Executor repositoryWorker;
    @Nullable
    private final RepositoryCache cache;
    private final EncryptionStorageManager encryptionStorageManager;

    public DefaultProjectManager(File rootDir, Executor repositoryWorker, Executor purgeWorker,
                                 MeterRegistry meterRegistry, @Nullable String cacheSpec,
                                 EncryptionStorageManager encryptionStorageManager) {
        super(rootDir, Project.class, purgeWorker);

        requireNonNull(meterRegistry, "meterRegistry");
        requireNonNull(repositoryWorker, "repositoryWorker");

        this.repositoryWorker = repositoryWorker;
        cache = cacheSpec != null ? new RepositoryCache(cacheSpec, meterRegistry) : null;
        this.encryptionStorageManager = requireNonNull(encryptionStorageManager, "encryptionStorageManager");

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
        return new DefaultProject(childDir, repositoryWorker, purgeWorker(), cache, encryptionStorageManager);
    }

    @Override
    protected Project createChild(
            File childDir, Author author, long creationTimeMillis, boolean encrypt) throws Exception {
        return new DefaultProject(childDir, repositoryWorker, purgeWorker(),
                                  creationTimeMillis, author, cache, encryptionStorageManager, encrypt);
    }

    @Override
    protected void closeChild(File childDir, Project child,
                              Supplier<CentralDogmaException> failureCauseSupplier) {
        final DefaultProject c = (DefaultProject) child;
        c.repos.close(failureCauseSupplier);
    }

    @Override
    protected CentralDogmaException newStorageExistsException(String name) {
        return new ProjectExistsException(name);
    }

    @Override
    protected CentralDogmaException newStorageNotFoundException(String name) {
        return new ProjectNotFoundException(name);
    }
}
