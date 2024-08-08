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

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.DiffResultType;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryListener;

public abstract class XdsProjectWatchingService {

    private static final Logger logger = LoggerFactory.getLogger(XdsProjectWatchingService.class);

    public static final long BACKOFF_SECONDS = 10;

    private final Project xdsProject;

    // Accessed only from executor().
    private final Set<String> watchingGroups = new HashSet<>();

    protected XdsProjectWatchingService(Project xdsProject) {
        this.xdsProject = xdsProject;
    }

    protected Project xdsProject() {
        return xdsProject;
    }

    protected abstract ScheduledExecutorService executor();

    protected abstract String pathPattern();

    protected abstract void handleXdsResources(String path, String contentAsText, String groupName)
            throws IOException;

    protected abstract void onGroupRemoved(String groupName);

    protected abstract void onFileRemoved(String groupName, String path);

    protected abstract void onDiffHandled();

    protected abstract boolean isStopped();

    /**
     * Must be executed by {@link #executor()}.
     */
    protected void start() {
        for (Repository repository : xdsProject.repos().list().values()) {
            final String groupName = repository.name();
            if (Project.internalRepos().contains(groupName)) {
                continue;
            }
            watchingGroups.add(groupName);
            final Revision normalizedRevision = repository.normalizeNow(Revision.HEAD);
            logger.info("Creating xDS resources from {} at revision: {}", groupName, normalizedRevision);
            final Map<String, Entry<?>> entries = repository.find(normalizedRevision, pathPattern()).join();
            for (Entry<?> entry : entries.values()) {
                final String path = entry.path();
                final String contentAsText = entry.contentAsText();
                try {
                    handleXdsResources(path, contentAsText, groupName);
                } catch (Throwable t) {
                    throw new RuntimeException("Unexpected exception while building an xDS resource from " +
                                               groupName + path, t);
                }
            }

            watchRepository(repository, normalizedRevision);
        }

        // Watch dogma repository to add newly created xDS projects.
        watchDogmaRepository();
    }

    private void watchDogmaRepository() {
        final Repository dogmaRepository = xdsProject.repos().get(Project.REPO_DOGMA);
        // TODO(minwoox): Use different file because metadata.json contains other information than repo's names.
        dogmaRepository.addListener(RepositoryListener.of(MetadataService.METADATA_JSON, entries -> {
            executor().execute(() -> {
                for (Repository repo : xdsProject.repos().list().values()) {
                    final String groupName = repo.name();
                    if (Project.REPO_META.equals(groupName) || Project.REPO_DOGMA.equals(groupName)) {
                        continue;
                    }
                    final boolean added = watchingGroups.add(groupName);
                    if (!added) {
                        // Already watching.
                        return;
                    }
                    watchRepository(repo, Revision.INIT);
                }
            });
        }));
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
                    watchingGroups.remove(repository.name());
                    onGroupRemoved(repository.name());
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
            final String groupName = repository.name();
            if (cause != null) {
                logger.warn("Unexpected exception while diffing {} from {} to {}. Watching again.",
                            groupName, lastKnownRevision, newRevision, cause);
                watchRepository(repository, Revision.INIT);
                return null;
            }

            logger.info("Found {} changes in {} from {} to {}.",
                        changes.size(), groupName, lastKnownRevision, newRevision);
            for (Change<?> change : changes.values()) {
                final String path = change.path();
                switch (change.type()) {
                    case UPSERT_JSON:
                        try {
                            handleXdsResources(path, change.contentAsText(), groupName);
                        } catch (Throwable t) {
                            logger.warn("Unexpected exception while handling an xDS resource from {}.",
                                        groupName + path, t);
                        }
                        break;
                    case REMOVE:
                        onFileRemoved(groupName, path);
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
