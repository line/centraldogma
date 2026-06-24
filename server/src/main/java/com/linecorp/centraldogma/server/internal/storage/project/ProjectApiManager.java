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
package com.linecorp.centraldogma.server.internal.storage.project;

import static com.linecorp.centraldogma.internal.Util.INTERNAL_PROJECT_PREFIX;
import static com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer.INTERNAL_PROJECT_DOGMA;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.PermissionException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageException;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

/**
 * A wrapper class of {@link ProjectManager} which prevents accessing internal projects
 * from unprivileged requests.
 */
public final class ProjectApiManager {

    // The xDS project is a self-service internal project: any authenticated user may access it (e.g. to list
    // and create groups via the xDS web UI). It is intentionally more permissive than other internal projects.
    private static final String INTERNAL_PROJECT_XDS = "@xds";

    private final ProjectManager projectManager;
    private final CommandExecutor commandExecutor;
    private final MetadataService metadataService;
    private final EncryptionStorageManager encryptionStorageManager;

    public ProjectApiManager(ProjectManager projectManager, CommandExecutor commandExecutor,
                             MetadataService metadataService,
                             EncryptionStorageManager encryptionStorageManager) {
        this.projectManager = projectManager;
        this.commandExecutor = commandExecutor;
        this.metadataService = metadataService;
        this.encryptionStorageManager = encryptionStorageManager;
    }

    public Map<String, Project> listProjects(@Nullable User user) {
        final Map<String, Project> projects = projectManager.list();
        if (isSystemAdmin()) {
            return projects;
        }

        return listProjectsWithoutInternal(projects, user);
    }

    private static boolean isSystemAdmin() {
        final User currentUserOrNull = AuthUtil.currentUserOrNull();
        if (currentUserOrNull == null) {
            return false;
        }

        return currentUserOrNull.isSystemAdmin();
    }

    public static Map<String, Project> listProjectsWithoutInternal(Map<String, Project> projects,
                                                                   @Nullable User user) {
        final Map<String, Project> result = new LinkedHashMap<>(projects.size() - 1);
        for (Map.Entry<String, Project> entry : projects.entrySet()) {
            if (isInternalProject(entry.getKey())) {
                // Only the accessible internal project (the xDS project) is shown, and only to authenticated
                // users.
                if (user != null && isAccessibleInternalProject(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Instant> listRemovedProjects() {
        return projectManager.listRemoved();
    }

    public CompletableFuture<Void> createProject(String projectName, Author author) {
        checkInternalProject(projectName, "create");
        if (!encryptionStorageManager.enabled()) {
            return commandExecutor.execute(Command.createProject(author, projectName));
        }
        return encryptionStorageManager.generateWdek()
                                       .thenCompose(wdek -> {
                                           final WrappedDekDetails wdekDetails = new WrappedDekDetails(
                                                   wdek, 1, encryptionStorageManager.kekId(),
                                                   projectName, Project.REPO_DOGMA);
                                           return commandExecutor.execute(
                                                   Command.createProject(author, projectName, wdekDetails));
                                       })
                                       .exceptionally(cause -> {
                                           throw new EncryptionStorageException(
                                                   "Failed to create encrypted project " + projectName, cause);
                                       });
    }

    private static void checkInternalProject(String projectName, String operation) {
        if (isInternalProject(projectName)) {
            throw new IllegalArgumentException("Cannot " + operation + ' ' + projectName);
        }
    }

    public CompletableFuture<ProjectMetadata> getProjectMetadata(String projectName) {
        return metadataService.getProject(projectName);
    }

    public CompletableFuture<Void> removeProject(String projectName, Author author) {
        checkInternalProject(projectName, "remove");
        // Metadata must be updated first because it cannot be updated if the project is removed.
        return metadataService.removeProject(author, projectName)
                              .thenCompose(unused -> commandExecutor.execute(
                                      Command.removeProject(author, projectName)));
    }

    public CompletableFuture<Void> purgeProject(String projectName, Author author) {
        checkInternalProject(projectName, "purge");
        return commandExecutor.execute(Command.purgeProject(author, projectName));
    }

    public CompletableFuture<Revision> unremoveProject(String projectName, Author author) {
        checkInternalProject(projectName, "unremove");
        // Restore the project first then update its metadata as 'active'.
        return commandExecutor.execute(Command.unremoveProject(author, projectName))
                              .thenCompose(unused -> metadataService.restoreProject(author, projectName));
    }

    public Project getProject(String projectName) {
        return getProject(projectName, AuthUtil.currentUserOrNull());
    }

    public Project getProject(String projectName, @Nullable User user) {
        final Project project = projectManager.get(projectName);

        if (!isInternalProject(projectName)) {
            return project;
        }

        if (user == null) {
            throw new IllegalArgumentException("Cannot access " + projectName);
        }

        if (user.isSystemAdmin()) {
            return project;
        }
        if (isAccessibleInternalProject(projectName)) {
            return project;
        }
        throw new PermissionException("Cannot access " + projectName);
    }

    /**
     * Returns whether the specified internal project is accessible through this API. Among the internal
     * projects, only the xDS project is accessible, and it is open to any authenticated user (e.g. to list and
     * create groups via the xDS web UI).
     */
    private static boolean isAccessibleInternalProject(String projectName) {
        return INTERNAL_PROJECT_XDS.equals(projectName);
    }

    private static boolean isInternalProject(String projectName) {
        return projectName.startsWith(INTERNAL_PROJECT_PREFIX) || INTERNAL_PROJECT_DOGMA.equals(projectName);
    }

    public boolean exists(String projectName) {
        // Apply the same access rule as getProject(): the xDS project is accessible to any authenticated user,
        // while the other internal projects remain accessible to system administrators only.
        if (isInternalProject(projectName)) {
            final User user = AuthUtil.currentUserOrNull();
            if (user == null) {
                throw new IllegalArgumentException("Cannot access " + projectName);
            }
            if (!user.isSystemAdmin() && !isAccessibleInternalProject(projectName)) {
                throw new PermissionException("Cannot access " + projectName);
            }
        }
        return projectManager.exists(projectName);
    }
}
