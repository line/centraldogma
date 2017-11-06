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

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.CacheStats;

import com.linecorp.centraldogma.server.internal.storage.DirectoryBasedStorageManager;
import com.linecorp.centraldogma.server.internal.storage.StorageExistsException;
import com.linecorp.centraldogma.server.internal.storage.StorageNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.cache.RepositoryCache;

public class DefaultProjectManager extends DirectoryBasedStorageManager<Project> implements ProjectManager {

    @Nullable
    private final RepositoryCache cache;

    public DefaultProjectManager(File rootDir, Executor repositoryWorker, @Nullable String cacheSpec) {
        super(rootDir, Project.class,
              requireNonNull(repositoryWorker, "repositoryWorker"),
              cacheSpec != null ? new RepositoryCache(cacheSpec) : null);
        cache = (RepositoryCache) childArg(1);
    }

    @Override
    public CacheStats cacheStats() {
        return cache != null ? cache.stats() : CacheStats.empty();
    }

    @Override
    public void close() {
        super.close();
        if (cache != null) {
            cache.clear();
        }
    }

    @Override
    protected Project openChild(File childDir, Object[] childArgs) throws Exception {
        return new DefaultProject(childDir, false, (Executor) childArgs[0], (RepositoryCache) childArgs[1]);
    }

    @Override
    protected Project createChild(File childDir, Object[] childArgs, long creationTimeMillis) throws Exception {
        return new DefaultProject(childDir, true, (Executor) childArgs[0], (RepositoryCache) childArgs[1]);
    }

    @Override
    protected void closeChild(File childDir, Project child) {
        DefaultProject c = (DefaultProject) child;
        c.repos.close();
    }

    @Override
    protected StorageExistsException newStorageExistsException(String name) {
        return new ProjectExistsException(name);
    }

    @Override
    protected StorageNotFoundException newStorageNotFoundException(String name) {
        return new ProjectNotFoundException(name);
    }
}
