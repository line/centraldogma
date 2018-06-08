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

import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.INTERNAL_PROJ;
import static com.linecorp.centraldogma.server.internal.metadata.MetadataService.METADATA_JSON;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.internal.metadata.Member;
import com.linecorp.centraldogma.server.internal.metadata.PerRolePermissions;
import com.linecorp.centraldogma.server.internal.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.internal.metadata.ProjectRole;
import com.linecorp.centraldogma.server.internal.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.internal.metadata.UserAndTimestamp;
import com.linecorp.centraldogma.server.internal.plugin.PluginManager;
import com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository;
import com.linecorp.centraldogma.server.internal.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.server.internal.storage.repository.cache.CachingRepositoryManager;
import com.linecorp.centraldogma.server.internal.storage.repository.cache.RepositoryCache;
import com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryManager;

class DefaultProject implements Project {

    private static final Logger logger = LoggerFactory.getLogger(DefaultProject.class);

    private final String name;
    final RepositoryManager repos;
    private final AtomicReference<MetaRepository> metaRepo = new AtomicReference<>();
    private PluginManager plugins;

    /**
     * Opens an existing project.
     */
    DefaultProject(File rootDir, Executor repositoryWorker, @Nullable RepositoryCache cache) {
        requireNonNull(rootDir, "rootDir");
        requireNonNull(repositoryWorker, "repositoryWorker");

        if (!rootDir.exists()) {
            throw new ProjectNotFoundException(rootDir.toString());
        }

        name = rootDir.getName();
        repos = newRepoManager(rootDir, repositoryWorker, cache);

        boolean success = false;
        try {
            createReservedRepos(System.currentTimeMillis(), Author.SYSTEM);
            success = true;
        } finally {
            if (!success) {
                repos.close(() -> new CentralDogmaException("failed to initialize internal repositories"));
            }
        }
    }

    /**
     * Creates a new project.
     */
    DefaultProject(File rootDir, Executor repositoryWorker, @Nullable RepositoryCache cache,
                   long creationTimeMillis, Author author) {
        requireNonNull(rootDir, "rootDir");
        requireNonNull(repositoryWorker, "repositoryWorker");

        if (rootDir.exists()) {
            throw new ProjectExistsException(rootDir.toString());
        }

        name = rootDir.getName();
        repos = newRepoManager(rootDir, repositoryWorker, cache);

        boolean success = false;
        try {
            createReservedRepos(creationTimeMillis, author);
            initializeMetadata(creationTimeMillis, author);
            success = true;
        } finally {
            if (!success) {
                repos.close(() -> new CentralDogmaException("failed to initialize internal repositories"));
            }
        }
    }

    private RepositoryManager newRepoManager(File rootDir, Executor repositoryWorker,
                                             @Nullable RepositoryCache cache) {
        // Enable caching if 'cache' is not null.
        final GitRepositoryManager gitRepos = new GitRepositoryManager(this, rootDir, repositoryWorker);
        return cache == null ? gitRepos : new CachingRepositoryManager(gitRepos, cache);
    }

    private void createReservedRepos(long creationTimeMillis, Author author) {
        if (!repos.exists(Project.REPO_DOGMA)) {
            try {
                repos.create(Project.REPO_DOGMA, creationTimeMillis, Author.SYSTEM);
            } catch (RepositoryExistsException ignored) {
                // Just in case there's a race.
            }
        }
        if (!repos.exists(Project.REPO_META)) {
            try {
                repos.create(Project.REPO_META, creationTimeMillis, Author.SYSTEM);
            } catch (RepositoryExistsException ignored) {
                // Just in case there's a race.
            }
        }
    }

    private void initializeMetadata(long creationTimeMillis, Author author) {
        // Do not generate a metadata file for internal projects.
        if (name.equals(INTERNAL_PROJ)) {
            return;
        }

        final Repository dogmaRepo = repos.get(Project.REPO_DOGMA);
        final Revision headRev = dogmaRepo.normalizeNow(Revision.HEAD);
        if (!dogmaRepo.exists(headRev, METADATA_JSON).join()) {
            logger.info("Initializing metadata: {}", name);

            final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);
            final RepositoryMetadata repo = new RepositoryMetadata(REPO_META, userAndTimestamp,
                                                                   PerRolePermissions.DEFAULT);
            final Member member = new Member(author, ProjectRole.OWNER, userAndTimestamp);
            final ProjectMetadata metadata = new ProjectMetadata(name,
                                                                 ImmutableMap.of(repo.id(), repo),
                                                                 ImmutableMap.of(member.id(), member),
                                                                 ImmutableMap.of(),
                                                                 userAndTimestamp, null);

            dogmaRepo.commit(headRev, creationTimeMillis, Author.SYSTEM,
                             "Initialize metadata", "", Markup.PLAINTEXT,
                             Change.ofJsonUpsert(METADATA_JSON, Jackson.valueToTree(metadata))).join();
        }
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
