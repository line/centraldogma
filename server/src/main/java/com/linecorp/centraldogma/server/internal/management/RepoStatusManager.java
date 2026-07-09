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
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.internal.storage.repository.crud.CrudContext;
import com.linecorp.centraldogma.server.internal.storage.repository.crud.CrudOperation;
import com.linecorp.centraldogma.server.internal.storage.repository.crud.StandaloneCrudOperation;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
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
        Gauge.builder("repository.read.only.count", statusMap, Map::size).register(meterRegistry);
        readOnlyScopeGauge = MultiGauge.builder("repository.read.only").register(meterRegistry);
    }

    public void initialize() {
        final Repository dogmaRepo = pm.get(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA).repos()
                                       .get(Project.REPO_DOGMA);
        dogmaRepo.addListener(RepositoryListener.of("/status/**/*.json", entries -> {
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
            updateReadOnlyScopeMetrics();
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
        return ImmutableList.copyOf(statusMap.values());
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

    private static CrudContext crudContext(String projectName) {
        final String targetPath = PATH_PREFIX + projectName + '/';
        return new CrudContext(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                               Project.REPO_DOGMA, targetPath);
    }

    private void updateReadOnlyScopeMetrics() {
        readOnlyScopeGauge.register(
                statusMap.values().stream()
                         .<MultiGauge.Row<?>>map(state -> MultiGauge.Row.of(
                                 Tags.of("project", state.projectName(),
                                         "repo", state.repoName(),
                                         "scope", scopeOf(state)),
                                 1))
                         .collect(toImmutableList()),
                true);
    }

    private static String scopeOf(RepositoryState state) {
        return state.repoName().equals(Project.REPO_DOGMA) ? "project" : "repository";
    }
}
