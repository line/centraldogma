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
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.DirectoryBasedStorageManager;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

public class GitRepositoryManager extends DirectoryBasedStorageManager<Repository>
        implements RepositoryManager {

    private final Project parent;
    private final Executor repositoryWorker;

    @Nullable
    private final RepositoryCache cache;

    public GitRepositoryManager(Project parent, File rootDir, Executor repositoryWorker,
                                Executor purgeWorker, @Nullable RepositoryCache cache) {
        super(rootDir, Repository.class, purgeWorker);
        this.parent = requireNonNull(parent, "parent");
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
        return new GitRepository(parent, childDir, repositoryWorker, cache);
    }

    @Override
    protected Repository createChild(File childDir, Author author, long creationTimeMillis) throws Exception {
        return new GitRepository(parent, childDir, repositoryWorker,
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
}
