/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.xds.internal;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.DiffResultType;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

public abstract class XdsProjectWatchingService {

    private static final Logger logger = LoggerFactory.getLogger(XdsProjectWatchingService.class);

    public static final long BACKOFF_SECONDS = 10;

    private final RepositoryManager repositoryManager;

    // Accessed only from executor().
    private final Set<String> watchingRepos = new HashSet<>();

    protected XdsProjectWatchingService(RepositoryManager repositoryManager) {
        this.repositoryManager = repositoryManager;
    }

    protected abstract ScheduledExecutorService executor();

    protected abstract String pathPattern();

    protected abstract void handleXdsResources(String path, String contentAsText, String repoName)
            throws InvalidProtocolBufferException;

    protected abstract void onRepositoryRemoved(String repoName);

    protected abstract void onFileRemoved(String repoName, String path);

    protected abstract void onDiffHandled();

    protected abstract boolean isStopped();

    /**
     * Must be executed by {@link #executor()}.
     */
    protected void start() {
        for (Repository repository : repositoryManager.list().values()) {
            final String repoName = repository.name();
            if (Project.internalRepos().contains(repoName)) {
                continue;
            }
            watchingRepos.add(repoName);
            final Revision normalizedRevision = repository.normalizeNow(Revision.HEAD);
            logger.info("Creating xDS resources from {} at revision: {}", repoName, normalizedRevision);
            final Map<String, Entry<?>> entries = repository.find(normalizedRevision, pathPattern()).join();
            for (Entry<?> entry : entries.values()) {
                final String path = entry.path();
                final String contentAsText = entry.contentAsText();
                try {
                    handleXdsResources(path, contentAsText, repoName);
                } catch (Throwable t) {
                    throw new RuntimeException("Unexpected exception while building an xDS resource from " +
                                               repoName + path, t);
                }
            }

            watchRepository(repository, normalizedRevision);
        }

        // Watch dogma repository to add newly created xDS projects.
        watchDogmaRepository(repositoryManager, Revision.INIT);
    }

    private void watchDogmaRepository(RepositoryManager repositoryManager, Revision lastKnownRevision) {
        final Repository dogmaRepository = repositoryManager.get(Project.REPO_DOGMA);
        // TODO(minwoox): Use different file because metadata.json contains other information than repo's names.
        dogmaRepository.watch(lastKnownRevision, Query.ofJson(MetadataService.METADATA_JSON))
                       .handleAsync((entry, cause) -> {
                           if (isStopped()) {
                               return null;
                           }

                           if (cause != null) {
                               logger.warn("Failed to watch {} in xDS. Try watching after {} seconds.",
                                           MetadataService.METADATA_JSON, BACKOFF_SECONDS, cause);
                               executor().schedule(
                                       () -> watchDogmaRepository(repositoryManager, lastKnownRevision),
                                       BACKOFF_SECONDS, TimeUnit.SECONDS);
                               return null;
                           }
                           final JsonNode content = entry.content();
                           final JsonNode repos = content.get("repos");
                           if (repos == null) {
                               logger.warn("Failed to find repos in {} in xDS. Try watching after {} seconds.",
                                           MetadataService.METADATA_JSON, BACKOFF_SECONDS);
                               executor().schedule(
                                       () -> watchDogmaRepository(repositoryManager, lastKnownRevision),
                                       BACKOFF_SECONDS, TimeUnit.SECONDS);
                               return null;
                           }
                           repos.fieldNames().forEachRemaining(repoName -> {
                               if (Project.REPO_META.equals(repoName)) {
                                   return;
                               }
                               final boolean added = watchingRepos.add(repoName);
                               if (!added) {
                                   // Already watching.
                                   return;
                               }
                               final Repository repository = repositoryManager.get(repoName);
                               if (repository == null) {
                                   // Ignore if the repository is removed. This can happen when multiple
                                   // updates occurred after the actual repository is removed from the file
                                   // system but before the repository is removed from the metadata file.
                                   watchingRepos.remove(repoName);
                                   return;
                               }
                               watchRepository(repository, Revision.INIT);
                           });
                           // Watch dogma repository again to catch up newly created xDS projects.
                           watchDogmaRepository(repositoryManager, entry.revision());
                           return null;
                       }, executor());
    }

    private void watchRepository(Repository repository, Revision lastKnownRevision) {
        final CompletableFuture<Revision> watchFuture = repository.watch(lastKnownRevision, pathPattern());
        watchFuture.handleAsync((BiFunction<Revision, Throwable, Void>) (newRevision, cause) -> {
            if (isStopped()) {
                return null;
            }
            if (cause != null) {
                if (cause instanceof RepositoryNotFoundException) {
                    // Repository is removed.
                    watchingRepos.remove(repository.name());
                    onRepositoryRemoved(repository.name());
                    return null;
                }
                logger.warn("Unexpected exception while watching {} at {}. Try watching after {} seconds.",
                            repository.name(), lastKnownRevision, BACKOFF_SECONDS, cause);
                executor().schedule(() -> watchRepository(repository, lastKnownRevision),
                                    BACKOFF_SECONDS, TimeUnit.SECONDS);
                return null;
            }
            final CompletableFuture<Map<String, Change<?>>> diffFuture =
                    repository.diff(lastKnownRevision, newRevision,
                                    pathPattern(), DiffResultType.PATCH_TO_UPSERT);
            handleDiff(repository, newRevision, diffFuture, lastKnownRevision);
            return null;
        }, executor());
    }

    private void handleDiff(Repository repository, Revision newRevision,
                            CompletableFuture<Map<String, Change<?>>> diffFuture, Revision lastKnownRevision) {
        diffFuture.handleAsync((BiFunction<Map<String, Change<?>>, Throwable, Void>) (changes, cause) -> {
            if (isStopped()) {
                return null;
            }
            final String repoName = repository.name();
            if (cause != null) {
                logger.warn("Unexpected exception while diffing {} from {} to {}. Watching again.",
                            repoName, lastKnownRevision, newRevision, cause);
                watchRepository(repository, Revision.INIT);
                return null;
            }

            logger.info("Found {} changes in {} from {} to {}.",
                        changes.size(), repoName, lastKnownRevision, newRevision);
            for (Change<?> change : changes.values()) {
                final String path = change.path();
                switch (change.type()) {
                    case UPSERT_JSON:
                        try {
                            handleXdsResources(path, change.contentAsText(), repoName);
                        } catch (Throwable t) {
                            logger.warn("Unexpected exception while handling an xDS resource from {}.",
                                        repoName + path, t);
                        }
                        break;
                    case REMOVE:
                        onFileRemoved(repoName, path);
                        break;
                    default:
                        // Ignore other types of changes.
                        // No APPLY_JSON_PATCH because the diff option.
                        // No RENAME because the resource name in the content always have to be
                        // changed if the file is renamed.
                        if (lastKnownRevision.major() != 1) {
                            logger.warn("Unexpected change type: {} from {} to {} at {}.",
                                        change.type(), lastKnownRevision, newRevision, path);
                        }
                        break;
                }
            }
            onDiffHandled();
            watchRepository(repository, newRevision);
            return null;
        }, executor());
    }
}
