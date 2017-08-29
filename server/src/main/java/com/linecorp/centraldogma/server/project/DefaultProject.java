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

package com.linecorp.centraldogma.server.project;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Util;
import com.linecorp.centraldogma.server.common.StorageException;
import com.linecorp.centraldogma.server.plugin.PluginManager;
import com.linecorp.centraldogma.server.repository.DefaultMetaRepository;
import com.linecorp.centraldogma.server.repository.MetaRepository;
import com.linecorp.centraldogma.server.repository.Repository;
import com.linecorp.centraldogma.server.repository.RepositoryManager;
import com.linecorp.centraldogma.server.repository.cache.CachingRepositoryManager;
import com.linecorp.centraldogma.server.repository.cache.RepositoryCache;
import com.linecorp.centraldogma.server.repository.git.GitRepositoryManager;

class DefaultProject implements Project {

    private final String name;
    final RepositoryManager repos;
    private final AtomicReference<MetaRepository> metaRepo = new AtomicReference<>();
    private PluginManager plugins;

    DefaultProject(File rootDir, boolean create, Executor repositoryWorker, @Nullable RepositoryCache cache) {
        requireNonNull(rootDir, "rootDir");
        requireNonNull(repositoryWorker, "repositoryWorker");

        if (create) {
            if (rootDir.exists()) {
                throw new StorageException("project exists already: " + rootDir);
            }
        } else {
            if (!rootDir.exists()) {
                throw new StorageException("non-existent project: " + rootDir);
            }
        }

        name = rootDir.getName();

        // Enable caching if 'cache' is not null.
        final GitRepositoryManager gitRepos = new GitRepositoryManager(this, rootDir, repositoryWorker);
        repos = cache == null ? gitRepos : new CachingRepositoryManager(gitRepos, cache);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public MetaRepository metaRepo() {
        MetaRepository metaRepo = this.metaRepo.get();
        if (metaRepo != null) {
            return metaRepo;
        }

        metaRepo = new DefaultMetaRepository(repos.get(REPO_META));
        if (this.metaRepo.compareAndSet(null, metaRepo)) {
            return metaRepo;
        } else {
            return this.metaRepo.get();
        }
    }

    @Override
    public Repository mainRepo() {
        return repos.get(REPO_MAIN);
    }

    @Override
    public RepositoryManager repos() {
        return repos;
    }

    @Override
    public synchronized PluginManager plugins() {
        if (plugins != null) {
            return plugins;
        }

        return plugins = new PluginManager(this);
    }

    @Override
    public String toString() {
        return Util.simpleTypeName(getClass()) + '(' + repos + ')';
    }
}
