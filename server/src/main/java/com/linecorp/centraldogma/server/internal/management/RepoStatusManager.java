/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.internal.management;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.internal.storage.repository.crud.CrudContext;
import com.linecorp.centraldogma.server.internal.storage.repository.crud.CrudOperation;
import com.linecorp.centraldogma.server.internal.storage.repository.crud.StandaloneCrudOperation;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryListener;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;

/**
 * Manages the replication status of repositories and projects. The replication status is stored in a special
 * repository named "dogma" in dogma project
 * (i.e. {@code <root>/dogma/dogma/status/{projectName}/{repoName}.json}) for simplicity.
 *
 * <p>Note that {@link RepoStatusManager} only updates the status in the local node and does not
 * replicate it to other nodes. The replication must be done by
 * {@link Command#updateRepositoryStatus(String, String, Author, ReplicationStatus)} or
 * {@link Command#updateProjectStatus(String, ReplicationStatus)}.
 */
public final class RepoStatusManager {

    private static final Logger logger = LoggerFactory.getLogger(RepoStatusManager.class);

    private static final String PATH_PREFIX = "/status/";

    private final Map<String, RepositoryState> statusMap = new ConcurrentHashMap<>();
    private final ProjectManager pm;
    @Nullable
    private final StandaloneCrudOperation<RepositoryState> crudRepository;
    private final ServerStatusManager statusManager;
    private final MultiGauge readOnlyScopeGauge;

    public RepoStatusManager(ServerStatusManager statusManager, ProjectManager pm,
                             MeterRegistry meterRegistry) {
        this.pm = pm;
        this.statusManager = statusManager;
        crudRepository = new StandaloneCrudOperation<>(RepositoryState.class, pm);

        // read-only scope metrics
        Gauge.builder("repository.read.only.count", this, RepoStatusManager::activeReadOnlyCount)
             .register(meterRegistry);
        readOnlyScopeGauge = MultiGauge.builder("repository.read.only").register(meterRegistry);
    }

    public void initialize() {
        final Repository dogmaRepo = pm.get(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA).repos()
                                       .get(Project.REPO_DOGMA);
        dogmaRepo.addListener(RepositoryListener.of("/status/**/*.json", entries -> {
            // This snapshot only ever contains files that still exist, so a stale/late-delivered
            // snapshot must never evict a key it does not observe. Deletions on purge are therefore
            // applied directly by removeRepoStatus/removeProjectStatus, not reconciled here.
            for (Entry<?> entry : entries.values()) {
                final RepositoryState repoState;
                try {
                    repoState = Jackson.treeToValue(entry.contentAsJson(), RepositoryState.class);
                } catch (JsonParseException | JsonMappingException e) {
                    logger.warn("Failed to parse the repository state from {}. Skipping it.", entry.path(), e);
                    continue;
                }
                logger.debug("Updating the repository state cache: {} -> {}", entry.path(), repoState);
                if (repoState.status() == ReplicationStatus.WRITABLE) {
                    // Remove the writable status from the cache to reduce the memory usage.
                    statusMap.remove(getKey(repoState.projectName(), repoState.repoName()));
                } else {
                    statusMap.put(getKey(repoState.projectName(), repoState.repoName()), repoState);
                }
            }
            refreshReadOnlyMetrics();
        }));
    }

    private CrudOperation<RepositoryState> crudOperation() {
        if (crudRepository == null) {
            throw new IllegalStateException("RepoStatusManager is not initialized yet.");
        }
        return crudRepository;
    }

    public CompletableFuture<Void> updateRepoStatus(String projectName, String repoName, Author author,
                                                    ReplicationStatus newStatus) {
        final CrudContext crudContext = crudContext(projectName);
        final RepositoryState entity = new RepositoryState(projectName, repoName, newStatus, Instant.now());
        final String description = "Update the replication status of '" + projectName + '/' + repoName +
                                   "' to " + newStatus;

        // File path: <root>/dogma/dogma/status/{projectName}/{repoName}.json
        return crudOperation().save(crudContext, repoName, entity, author, description)
                              .handle((unused, cause) -> {
                                  if (cause != null) {
                                      cause = Exceptions.peel(cause);
                                      if (cause instanceof RedundantChangeException) {
                                          // Silently ignore the exception if the status is already updated by
                                          // another command.
                                          return null;
                                      }
                                      return Exceptions.throwUnsafely(cause);
                                  }
                                  return null;
                              });
    }

    public RepositoryState getRepoStatus(String projectName, String repoName) {
        final RepositoryState repoStatus = getRepoStatus0(projectName, repoName);
        if (repoStatus != null) {
            return repoStatus;
        }
        return new RepositoryState(projectName, repoName, ReplicationStatus.WRITABLE, null);
    }

    /**
     * Returns the {@link RepositoryState}s of all projects and repositories that are currently not
     * {@link ReplicationStatus#WRITABLE}. A project-scoped read-only entry uses {@link Project#REPO_DOGMA}
     * as its repository name.
     */
    public List<RepositoryState> readOnlyStatuses() {
        // Hide entries whose project/repository was removed; the preserved file restores it on unremove.
        return statusMap.values().stream()
                        .filter(state -> isActive(state.projectName(), state.repoName()))
                        .collect(toImmutableList());
    }

    @Nullable
    private RepositoryState getRepoStatus0(String projectName, String repoName) {
        if (!statusManager.serverStatus().writable()) {
            // The server is read-only.
            return new RepositoryState(projectName, repoName, ReplicationStatus.READ_ONLY, null);
        }

        String key = getKey(projectName, Project.REPO_DOGMA);
        final RepositoryState status = statusMap.get(key);
        if (status != null && status.status() == ReplicationStatus.READ_ONLY) {
            // The project is read-only.
            return new RepositoryState(projectName, repoName, ReplicationStatus.READ_ONLY, null);
        }

        if (repoName.equals(Project.REPO_DOGMA)) {
            return status;
        }

        key = getKey(projectName, repoName);
        return statusMap.get(key);
    }

    private static String getKey(String projectName, String repoName) {
        return projectName + '/' + repoName;
    }

    /**
     * Returns {@code true} if the repository or its project is read-only by the replicated repository or
     * project scope. Unlike {@link #isWritable(String, String)}, the per-replica server status is not
     * consulted, so the answer is identical on every replica at the same replication-log position.
     *
     * <p>A read-only answer from the in-memory cache is authoritative, but a writable answer is
     * re-verified against the replicated status files: the cache is loaded by an asynchronously
     * registered listener, so it can still be empty while the replication log is replayed after a
     * restart, and answering "writable" from an unloaded cache would make this replica disagree with
     * the rest of the cluster.
     */
    public boolean isRepoOrProjectReadOnly(String projectName, String repoName) {
        if (isCachedReadOnly(projectName, Project.REPO_DOGMA) || isCachedReadOnly(projectName, repoName)) {
            return true;
        }
        return isStoredReadOnly(projectName, Project.REPO_DOGMA) ||
               (!repoName.equals(Project.REPO_DOGMA) && isStoredReadOnly(projectName, repoName));
    }

    private boolean isCachedReadOnly(String projectName, String repoName) {
        final RepositoryState state = statusMap.get(getKey(projectName, repoName));
        return state != null && state.status() == ReplicationStatus.READ_ONLY;
    }

    private boolean isStoredReadOnly(String projectName, String repoName) {
        final HasRevision<RepositoryState> state;
        try {
            state = crudOperation().find(crudContext(projectName), repoName).join();
        } catch (CompletionException e) {
            final Throwable peeled = Exceptions.peel(e);
            if (peeled instanceof ProjectNotFoundException || peeled instanceof RepositoryNotFoundException) {
                // The internal status storage does not exist yet, so no status was ever replicated.
                return false;
            }
            throw e;
        }
        return state != null && state.object().status() == ReplicationStatus.READ_ONLY;
    }

    public boolean isWritable(String projectName, String repoName) {
        if (!statusManager.serverStatus().writable()) {
            return false;
        }

        final RepositoryState projectState = getRepoStatus0(projectName, Project.REPO_DOGMA);
        if (projectState != null && projectState.status() == ReplicationStatus.READ_ONLY) {
            return false;
        }

        if (!repoName.equals(Project.REPO_DOGMA)) {
            final RepositoryState repoState = getRepoStatus0(projectName, repoName);
            //noinspection RedundantIfStatement
            if (repoState != null && repoState.status() == ReplicationStatus.READ_ONLY) {
                return false;
            }
        }

        // Both the project and the repository are writable.
        return true;
    }

    public CompletableFuture<Void> updateProjectStatus(String projectName, Author author,
                                                       ReplicationStatus newStatus) {
        // The project status is stored in the repository named "dogma"
        // (i.e. <root>/dogma/dogma/status/{projectName}/dogma.json) for simplicity.
        return updateRepoStatus(projectName, Project.REPO_DOGMA, author, newStatus);
    }

    /**
     * Deletes the replication status file of the specified repository, if it is read-only.
     * Called when a repository is purged so that the cache and metrics do not leak the removed entry.
     */
    public CompletableFuture<Void> removeRepoStatus(String projectName, String repoName, Author author) {
        final String key = getKey(projectName, repoName);
        if (!statusMap.containsKey(key)) {
            // Not read-only in the cache; nothing to evict. (A writable status file, if any, is
            // harmless: it is filtered out of the read-only list/metrics and never re-marks a repo.)
            return CompletableFuture.completedFuture(null);
        }
        final String description = "Delete the replication status of '" + projectName + '/' + repoName + '\'';
        return crudOperation().delete(crudContext(projectName), repoName, author, description)
                              .handle((revision, cause) -> {
                                  if (cause != null) {
                                      final Throwable peeled = Exceptions.peel(cause);
                                      if (!(peeled instanceof ChangeConflictException ||
                                            peeled instanceof RedundantChangeException)) {
                                          return Exceptions.throwUnsafely(peeled);
                                      }
                                      // The status file was already removed; still evict the cache below.
                                  }
                                  // The listener does not observe deletions, so evict directly.
                                  statusMap.remove(key);
                                  refreshReadOnlyMetrics();
                                  return null;
                              });
    }

    /**
     * Deletes all replication status files of the specified project, if any. Called when a project is
     * purged so that the cache and metrics do not leak the removed entries.
     */
    public CompletableFuture<Void> removeProjectStatus(String projectName, Author author) {
        final String prefix = projectName + '/';
        final List<String> repoNames = statusMap.keySet().stream()
                                                 .filter(key -> key.startsWith(prefix))
                                                 .map(key -> key.substring(prefix.length()))
                                                 .collect(toImmutableList());
        // Clean up each repository independently so a single failure does not skip the rest.
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (String repoName : repoNames) {
            future = future.thenCompose(unused -> removeRepoStatus(projectName, repoName, author)
                    .exceptionally(cause -> {
                        logger.warn("Failed to remove the replication status of '{}/{}'.",
                                    projectName, repoName, cause);
                        return null;
                    }));
        }
        return future;
    }

    private static CrudContext crudContext(String projectName) {
        final String targetPath = PATH_PREFIX + projectName + '/';
        return new CrudContext(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                               Project.REPO_DOGMA, targetPath);
    }

    /**
     * Re-registers the {@code repository.read.only} gauge. Invoked by the repository listener on
     * status changes and by the command executor when a repository/project is removed, restored or
     * purged (which change {@link #isActive} without touching the status files).
     */
    public synchronized void refreshReadOnlyMetrics() {
        try {
            readOnlyScopeGauge.register(
                    statusMap.values().stream()
                             .filter(state -> isActive(state.projectName(), state.repoName()))
                             .<MultiGauge.Row<?>>map(state -> MultiGauge.Row.of(
                                     Tags.of("project", state.projectName(),
                                             "repo", state.repoName()),
                                     1))
                             .collect(toImmutableList()),
                    true);
        } catch (Exception e) {
            // Never let a metrics refresh failure propagate into the command that triggered it.
            logger.warn("Failed to refresh the read-only scope metrics.", e);
        }
    }

    private double activeReadOnlyCount() {
        return statusMap.values().stream()
                        .filter(state -> isActive(state.projectName(), state.repoName()))
                        .count();
    }

    /**
     * Returns {@code true} if the project and repository of a read-only entry still exist, i.e. they
     * have not been soft-removed or purged.
     */
    private boolean isActive(String projectName, String repoName) {
        try {
            if (!pm.exists(projectName)) {
                return false;
            }
            if (Project.REPO_DOGMA.equals(repoName)) {
                // Project-scoped entry; the project itself exists.
                return true;
            }
            return pm.get(projectName).repos().exists(repoName);
        } catch (CentralDogmaException e) {
            // The project/repository was removed concurrently; treat it as inactive.
            return false;
        }
    }
}
