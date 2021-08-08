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

import static com.linecorp.centraldogma.server.internal.storage.project.ProjectInitializer.INTERNAL_PROJECT_DOGMA;
import static com.linecorp.centraldogma.server.metadata.MetadataService.METADATA_JSON;
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
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.jackson.Jackson;
import com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.internal.storage.repository.cache.CachingRepositoryManager;
import com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryManager;
import com.linecorp.centraldogma.server.metadata.Member;
import com.linecorp.centraldogma.server.metadata.PerRolePermissions;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.ProjectRole;
import com.linecorp.centraldogma.server.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.metadata.UserAndTimestamp;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

public class DefaultProject implements Project {

    private static final Logger logger = LoggerFactory.getLogger(DefaultProject.class);

    private final String name;
    final RepositoryManager repos;
    private final AtomicReference<MetaRepository> metaRepo = new AtomicReference<>();

    /**
     * Opens an existing project.
     */
    DefaultProject(File rootDir, Executor repositoryWorker, Executor purgeWorker,
                   @Nullable RepositoryCache cache) {
        requireNonNull(rootDir, "rootDir");
        requireNonNull(repositoryWorker, "repositoryWorker");

        if (!rootDir.exists()) {
            throw new ProjectNotFoundException(rootDir.toString());
        }

        name = rootDir.getName();
        repos = newRepoManager(rootDir, repositoryWorker, purgeWorker, cache);

        boolean success = false;
        try {
            createReservedRepos(System.currentTimeMillis());
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
    DefaultProject(File rootDir, Executor repositoryWorker, Executor purgeWorker,
                   long creationTimeMillis, Author author, @Nullable RepositoryCache cache) {
        requireNonNull(rootDir, "rootDir");
        requireNonNull(repositoryWorker, "repositoryWorker");

        if (rootDir.exists()) {
            throw new ProjectExistsException(rootDir.getName());
        }

        name = rootDir.getName();
        repos = newRepoManager(rootDir, repositoryWorker, purgeWorker, cache);

        boolean success = false;
        try {
            createReservedRepos(creationTimeMillis);
            initializeMetadata(creationTimeMillis, author);
            success = true;
        } finally {
            if (!success) {
                repos.close(() -> new CentralDogmaException("failed to initialize internal repositories"));
            }
        }
    }

    private RepositoryManager newRepoManager(File rootDir, Executor repositoryWorker, Executor purgeWorker,
                                             @Nullable RepositoryCache cache) {
        // Enable caching if 'cache' is not null.
        final GitRepositoryManager gitRepos =
                new GitRepositoryManager(this, rootDir, repositoryWorker, purgeWorker, cache);
        return cache == null ? gitRepos : new CachingRepositoryManager(gitRepos, cache);
    }

    private void createReservedRepos(long creationTimeMillis) {
        if (!repos.exists(REPO_DOGMA)) {
            try {
                repos.create(REPO_DOGMA, creationTimeMillis, Author.SYSTEM);
            } catch (RepositoryExistsException ignored) {
                // Just in case there's a race.
            }
        }
        if (!repos.exists(REPO_META)) {
            try {
                repos.create(REPO_META, creationTimeMillis, Author.SYSTEM);
            } catch (RepositoryExistsException ignored) {
                // Just in case there's a race.
            }
        }
    }

    private void initializeMetadata(long creationTimeMillis, Author author) {
        // Do not generate a metadata file for internal projects.
        if (name.equals(INTERNAL_PROJECT_DOGMA)) {
            return;
        }

        final Repository dogmaRepo = repos.get(REPO_DOGMA);
        final Revision headRev = dogmaRepo.normalizeNow(Revision.HEAD);
        if (!dogmaRepo.exists(headRev, METADATA_JSON).join()) {
            logger.info("Initializing metadata: {}", name);

            final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);
            final RepositoryMetadata repo = new RepositoryMetadata(REPO_META, userAndTimestamp,
                                                                   PerRolePermissions.ofInternal());
            final Member member = new Member(author, ProjectRole.OWNER, userAndTimestamp);
            final ProjectMetadata metadata = new ProjectMetadata(name,
                                                                 ImmutableMap.of(repo.id(), repo),
                                                                 ImmutableMap.of(member.id(), member),
                                                                 ImmutableMap.of(),
                                                                 userAndTimestamp, null);

            dogmaRepo.commit(headRev, creationTimeMillis, Author.SYSTEM,
                             "Initialize metadata", "", Markup.PLAINTEXT,
                             Change.ofJsonUpsert(METADATA_JSON, Jackson.ofJson().valueToTree(metadata))).join();
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
    public String toString() {
        return Util.simpleTypeName(getClass()) + '(' + repos + ')';
    }
}
