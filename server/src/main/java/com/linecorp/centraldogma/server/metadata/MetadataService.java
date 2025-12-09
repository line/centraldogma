/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.centraldogma.server.metadata;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation.asJsonArray;
import static com.linecorp.centraldogma.internal.jsonpatch.JsonPatchUtil.encodeSegment;
import static com.linecorp.centraldogma.server.internal.storage.project.ProjectApiManager.listProjectsWithoutInternal;
import static com.linecorp.centraldogma.server.metadata.RepositoryMetadata.DEFAULT_PROJECT_ROLES;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.RepositoryStatus;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.metadata.ProjectMetadataTransformer;
import com.linecorp.centraldogma.server.management.ServerStatus;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

/**
 * A service class for metadata management.
 */
public class MetadataService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataService.class);

    /**
     * A path of metadata file.
     */
    public static final String METADATA_JSON = "/metadata.json";

    /**
     * A path of token list file.
     */
    // TODO(minwoox): Rename to /application-registry.json
    public static final String TOKEN_JSON = "/tokens.json";

    /**
     * A {@link JsonPointer} of project removal information.
     */
    private static final JsonPointer PROJECT_REMOVAL = JsonPointer.compile("/removal");

    private final ProjectManager projectManager;
    private final RepositorySupport<ProjectMetadata> metadataRepo;
    private final ApplicationService applicationService;

    private final Map<String, CompletableFuture<Revision>> reposInAddingMetadata = new ConcurrentHashMap<>();

    /**
     * Creates a new instance.
     */
    public MetadataService(ProjectManager projectManager, CommandExecutor executor,
                           InternalProjectInitializer projectInitializer) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
        metadataRepo = new RepositorySupport<>(projectManager, executor, ProjectMetadata.class);
        applicationService = new ApplicationService(projectManager, executor, projectInitializer);
    }

    /**
     * Returns a {@link ProjectMetadata} whose name equals to the specified {@code projectName}.
     */
    public CompletableFuture<ProjectMetadata> getProject(String projectName) {
        requireNonNull(projectName, "projectName");
        return getOrFetchMetadata(projectName);
    }

    private CompletableFuture<ProjectMetadata> getOrFetchMetadata(String projectName) {
        final ProjectMetadata metadata = getMetadata(projectName);
        final Set<String> reposWithMetadata = metadata.repos().keySet();
        final Set<String> repos = projectManager.get(projectName).repos().list().keySet();

        // Make sure all repositories have metadata. If not, create missing metadata.
        // A repository can have missing metadata when a dev forgot to call `addRepo()`
        // after creating a new repository.
        final ImmutableList.Builder<CompletableFuture<Revision>> builder = ImmutableList.builder();
        for (String repo : repos) {
            if (reposWithMetadata.contains(repo) || Project.isInternalRepo(repo)) {
                continue;
            }

            final String projectAndRepositoryName = projectName + '/' + repo;
            final CompletableFuture<Revision> future = new CompletableFuture<>();
            final CompletableFuture<Revision> futureInMap =
                    reposInAddingMetadata.computeIfAbsent(projectAndRepositoryName, key -> future);
            if (futureInMap != future) { // The metadata is already in adding.
                builder.add(futureInMap);
                continue;
            }

            logger.warn("Adding missing repository metadata: {}/{}", projectName, repo);
            final Author author = projectManager.get(projectName).repos().get(repo).author();
            final CompletableFuture<Revision> addRepoFuture = addRepo(author, projectName, repo);
            addRepoFuture.handle((revision, cause) -> {
                if (cause != null) {
                    future.completeExceptionally(cause);
                } else {
                    future.complete(revision);
                }
                reposInAddingMetadata.remove(projectAndRepositoryName);
                return null;
            });
            builder.add(future);
        }

        final ImmutableList<CompletableFuture<Revision>> futures = builder.build();
        if (futures.isEmpty()) {
            // All repositories have metadata.
            return CompletableFuture.completedFuture(metadata);
        }

        // Some repository did not have metadata and thus will add the missing ones.
        return CompletableFutures.successfulAsList(futures, cause -> {
            final Throwable peeled = Exceptions.peel(cause);
            // The metadata of the repository is added by another worker, so we can ignore the exception.
            if (peeled instanceof RepositoryExistsException) {
                return null;
            }
            return Exceptions.throwUnsafely(cause);
        }).thenCompose(unused -> {
            logger.info("Fetching {}/{}{} again",
                        projectName, Project.REPO_DOGMA, METADATA_JSON);
            return fetchMetadata(projectName);
        });
    }

    private ProjectMetadata getMetadata(String projectName) {
        final Project project = projectManager.get(projectName);
        final ProjectMetadata metadata = project.metadata();
        if (metadata == null) {
            throw new EntryNotFoundException("project metadata not found: " + projectName);
        }
        return metadata;
    }

    CompletableFuture<ProjectMetadata> fetchMetadata(String projectName) {
        return metadataRepo.fetch(projectName, Project.REPO_DOGMA, METADATA_JSON)
                           .thenApply(HolderWithRevision::object);
    }

    /**
     * Removes a {@link ProjectMetadata} whose name equals to the specified {@code projectName}.
     */
    public CompletableFuture<Revision> removeProject(Author author, String projectName) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");

        final Change<JsonNode> change = Change.ofJsonPatch(
                METADATA_JSON,
                asJsonArray(JsonPatchOperation.testAbsence(PROJECT_REMOVAL),
                            JsonPatchOperation.add(PROJECT_REMOVAL,
                                                   Jackson.valueToTree(UserAndTimestamp.of(author)))));
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author,
                                 "Remove the project: " + projectName, change);
    }

    /**
     * Restores a {@link ProjectMetadata} whose name equals to the specified {@code projectName}.
     */
    public CompletableFuture<Revision> restoreProject(Author author, String projectName) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");

        final Change<JsonNode> change =
                Change.ofJsonPatch(METADATA_JSON, JsonPatchOperation.remove(PROJECT_REMOVAL).toJsonNode());
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author,
                                 "Restore the project: " + projectName, change);
    }

    /**
     * Returns a {@link Member} if the specified {@link User} is a member of the specified {@code projectName}.
     */
    public CompletableFuture<Member> getMember(String projectName, User user) {
        requireNonNull(projectName, "projectName");
        requireNonNull(user, "user");

        return getProject(projectName).thenApply(
                project -> project.memberOrDefault(user.id(), null));
    }

    /**
     * Adds the specified {@code member} to the {@link ProjectMetadata} of the specified {@code projectName}
     * with the specified {@code projectRole}.
     */
    public CompletableFuture<Revision> addMember(Author author, String projectName,
                                                 User member, ProjectRole projectRole) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(member, "member");
        requireNonNull(projectRole, "projectRole");

        final Member newMember = new Member(member, projectRole, UserAndTimestamp.of(author));
        final JsonPointer path = JsonPointer.compile("/members" + encodeSegment(newMember.id()));
        final Change<JsonNode> change =
                Change.ofJsonPatch(METADATA_JSON,
                                   asJsonArray(JsonPatchOperation.testAbsence(path),
                                               JsonPatchOperation.add(path, Jackson.valueToTree(newMember))));
        final String commitSummary =
                "Add a member '" + newMember.id() + "' to the project '" + projectName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, change);
    }

    /**
     * Removes the specified {@code user} from the {@link ProjectMetadata} in the specified
     * {@code projectName}. It also removes the {@link RepositoryRole} of the specified {@code user} from every
     * {@link RepositoryMetadata}.
     */
    public CompletableFuture<Revision> removeMember(Author author, String projectName, User user) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(user, "user");

        final String memberId = user.id();
        final String commitSummary =
                "Remove the member '" + memberId + "' from the project '" + projectName + '\'';

        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    projectMetadata.member(memberId); // Raises an exception if the member does not exist.
                    final Map<String, Member> newMembers = removeFromMap(projectMetadata.members(), memberId);
                    final ImmutableMap<String, RepositoryMetadata> newRepos =
                            removeMemberFromRepositories(projectMetadata, memberId);
                    return new ProjectMetadata(projectMetadata.name(),
                                               newRepos,
                                               newMembers,
                                               projectMetadata.applications(),
                                               projectMetadata.creation(),
                                               projectMetadata.removal());
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    private static ImmutableMap<String, RepositoryMetadata> removeMemberFromRepositories(
            ProjectMetadata projectMetadata, String memberId) {
        final ImmutableMap.Builder<String, RepositoryMetadata> reposBuilder =
                ImmutableMap.builderWithExpectedSize(projectMetadata.repos().size());
        for (Entry<String, RepositoryMetadata> entry : projectMetadata.repos().entrySet()) {
            final RepositoryMetadata repositoryMetadata = entry.getValue();
            final Roles roles = repositoryMetadata.roles();
            final Map<String, RepositoryRole> users = roles.users();
            if (users.get(memberId) != null) {
                final ImmutableMap<String, RepositoryRole> newUsers = removeFromMap(users, memberId);
                final Roles newRoles = new Roles(roles.projectRoles(), newUsers, roles.applications());
                reposBuilder.put(entry.getKey(),
                                 new RepositoryMetadata(repositoryMetadata.name(),
                                                        newRoles,
                                                        repositoryMetadata.creation(),
                                                        repositoryMetadata.removal(),
                                                        repositoryMetadata.status()));
            } else {
                reposBuilder.put(entry);
            }
        }
        return reposBuilder.build();
    }

    /**
     * Updates a {@link ProjectRole} for the specified {@code member} in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> updateMemberRole(Author author, String projectName,
                                                        User member, ProjectRole projectRole) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(member, "member");
        requireNonNull(projectRole, "projectRole");

        final Change<JsonNode> change = Change.ofJsonPatch(
                METADATA_JSON,
                JsonPatchOperation.replace(
                        JsonPointer.compile("/members" + encodeSegment(member.id()) + "/role"),
                        Jackson.valueToTree(projectRole)).toJsonNode());
        final String commitSummary = "Updates the role of the member '" + member.id() +
                                     "' as '" + projectRole + "' for the project '" + projectName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, change);
    }

    /**
     * Returns a {@link RepositoryMetadata} of the specified {@code repoName} in the specified
     * {@code projectName}.
     */
    public CompletableFuture<RepositoryMetadata> getRepo(String projectName, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        return getProject(projectName).thenApply(project -> project.repo(repoName));
    }

    /**
     * Adds a {@link RepositoryMetadata} of the specified {@code repoName} to the specified {@code projectName}
     * with a default {@link RepositoryRole}. The member will have the {@link RepositoryRole#WRITE} role and
     * the guest won't have any role.
     */
    public CompletableFuture<Revision> addRepo(Author author, String projectName, String repoName) {
        return addRepo(author, projectName, repoName, DEFAULT_PROJECT_ROLES);
    }

    /**
     * Adds a {@link RepositoryMetadata} of the specified {@code repoName} to the specified {@code projectName}
     * with the specified {@link ProjectRoles}.
     */
    public CompletableFuture<Revision> addRepo(Author author, String projectName, String repoName,
                                               ProjectRoles projectRoles) {
        return addRepo(author, projectName, repoName,
                       RepositoryMetadata.of(repoName, UserAndTimestamp.of(author), projectRoles));
    }

    /**
     * Adds a {@link RepositoryMetadata} of the specified {@code repoName} to the specified {@code projectName}
     * with a default {@link RepositoryRole}. The member will have the {@link RepositoryRole#WRITE} role and
     * the guest won't have any role.
     */
    public CompletableFuture<Revision> addRepo(Author author, String projectName,
                                               String repoName, RepositoryMetadata repositoryMetadata) {
        // TODO(minwoox): Prohibit adding internal repositories after migration is done.
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(repositoryMetadata, "repositoryMetadata");

        final JsonPointer path = JsonPointer.compile("/repos" + encodeSegment(repoName));
        final Change<JsonNode> change =
                Change.ofJsonPatch(METADATA_JSON,
                                   asJsonArray(JsonPatchOperation.testAbsence(path),
                                               JsonPatchOperation.add(
                                                       path, Jackson.valueToTree(repositoryMetadata))));
        final String commitSummary =
                "Add a repo '" + repositoryMetadata.id() + "' to the project '" + projectName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, change)
                           .handle((revision, cause) -> {
                               if (cause != null) {
                                   if (Exceptions.peel(cause) instanceof ChangeConflictException) {
                                       throw RepositoryExistsException.of(projectName, repoName);
                                   } else {
                                       return Exceptions.throwUnsafely(cause);
                                   }
                               }
                               return revision;
                           });
    }

    /**
     * Removes a {@link RepositoryMetadata} of the specified {@code repoName} from the specified
     * {@code projectName}.
     */
    public CompletableFuture<Revision> removeRepo(Author author, String projectName, String repoName) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        final JsonPointer path = JsonPointer.compile("/repos" + encodeSegment(repoName) + "/removal");
        final Change<JsonNode> change =
                Change.ofJsonPatch(METADATA_JSON,
                                   asJsonArray(JsonPatchOperation.testAbsence(path),
                                               JsonPatchOperation.add(path, Jackson.valueToTree(
                                                       UserAndTimestamp.of(author)))));
        final String commitSummary =
                "Remove the repo '" + repoName + "' from the project '" + projectName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, change);
    }

    /**
     * Purges a {@link RepositoryMetadata} of the specified {@code repoName} from the specified
     * {@code projectName}.
     */
    public CompletableFuture<Revision> purgeRepo(Author author, String projectName, String repoName) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        final JsonPointer path = JsonPointer.compile("/repos" + encodeSegment(repoName));
        final Change<JsonNode> change = Change.ofJsonPatch(METADATA_JSON,
                                                           JsonPatchOperation.remove(path).toJsonNode());
        final String commitSummary =
                "Purge the repo '" + repoName + "' from the project '" + projectName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, change);
    }

    /**
     * Restores a {@link RepositoryMetadata} of the specified {@code repoName} in the specified
     * {@code projectName}.
     */
    public CompletableFuture<Revision> restoreRepo(Author author, String projectName, String repoName) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        final Change<JsonNode> change =
                Change.ofJsonPatch(METADATA_JSON,
                                   JsonPatchOperation.remove(JsonPointer.compile(
                                           "/repos" + encodeSegment(repoName) + "/removal")).toJsonNode());
        final String commitSummary =
                "Restore the repo '" + repoName + "' from the project '" + projectName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, change);
    }

    /**
     * Updates the member and guest {@link RepositoryRole} of the specified {@code repoName} in the specified
     * {@code projectName}.
     */
    public CompletableFuture<Revision> updateRepositoryProjectRoles(Author author,
                                                                    String projectName, String repoName,
                                                                    ProjectRoles projectRoles) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        if (Project.isInternalRepo(repoName)) {
            throw new UnsupportedOperationException(
                    "Can't update role for internal repository: " + repoName);
        }

        final String commitSummary =
                "Update the project roles of the '" + repoName + "' in the project '" + projectName + '\'';
        final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                repoName, (headRevision, repositoryMetadata) -> {
            final Roles newRoles = new Roles(projectRoles, repositoryMetadata.roles().users(),
                                             repositoryMetadata.roles().applications());
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.status());
        });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Adds an {@link Application} of the specified {@code appId} to the specified {@code projectName}.
     */
    public CompletableFuture<Revision> addApplication(Author author, String projectName,
                                                      String appId, ProjectRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(appId, "appId");
        requireNonNull(role, "role");

        applicationService.getApplicationRegistry().get(appId); // Will raise an exception if not found.
        final ApplicationRegistration registration = new ApplicationRegistration(appId, role,
                                                                                 UserAndTimestamp.of(author));
        final JsonPointer path = JsonPointer.compile("/applications" + encodeSegment(registration.id()));
        final Change<JsonNode> change =
                Change.ofJsonPatch(METADATA_JSON,
                                       asJsonArray(JsonPatchOperation.testAbsence(path),
                                                   JsonPatchOperation.add(path,
                                                                          Jackson.valueToTree(registration))));
        final String commitSummary = "Add an application '" + registration.id() +
                                     "' to the project '" + projectName + "' with a role '" + role + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, change);
    }

    /**
     * Removes the {@link Application} of the specified {@code appId} from the specified {@code projectName}.
     * It also removes every application repository role belonging to {@link Application} from
     * every {@link RepositoryMetadata}.
     */
    public CompletableFuture<Revision> removeApplicationFromProject(Author author,
                                                                    String projectName, String appId) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(appId, "appId");

        return removeApplicationFromProject(author, projectName, appId, false);
    }

    CompletableFuture<Revision> removeApplicationFromProject(Author author, String projectName, String appId,
                                                             boolean quiet) {
        final String commitSummary =
                "Remove the application '" + appId + "' from the project '" + projectName + '\'';
        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    final Map<String, ApplicationRegistration> applications = projectMetadata.applications();
                    final Map<String, ApplicationRegistration> newApplications;
                    if (applications.get(appId) == null) {
                        if (!quiet) {
                            throw new ApplicationNotFoundException(
                                    "failed to find the application " + appId + " in project " + projectName);
                        }
                        newApplications = applications;
                    } else {
                        newApplications = applications.entrySet()
                                                      .stream()
                                                      .filter(entry -> !entry.getKey().equals(appId))
                                                      .collect(toImmutableMap(Entry::getKey, Entry::getValue));
                    }

                    final ImmutableMap<String, RepositoryMetadata> newRepos =
                            removeApplicationFromRepositories(appId, projectMetadata);
                    return new ProjectMetadata(projectMetadata.name(),
                                               newRepos,
                                               projectMetadata.members(),
                                               newApplications,
                                               projectMetadata.creation(),
                                               projectMetadata.removal());
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    private static ImmutableMap<String, RepositoryMetadata> removeApplicationFromRepositories(
            String appId, ProjectMetadata projectMetadata) {
        final ImmutableMap.Builder<String, RepositoryMetadata> builder =
                ImmutableMap.builderWithExpectedSize(projectMetadata.repos().size());
        for (Entry<String, RepositoryMetadata> entry : projectMetadata.repos().entrySet()) {
            final RepositoryMetadata repositoryMetadata = entry.getValue();
            final Roles roles = repositoryMetadata.roles();
            if (roles.applications().get(appId) != null) {
                final Map<String, RepositoryRole> newApplications = removeFromMap(roles.applications(), appId);
                final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), newApplications);
                builder.put(entry.getKey(), new RepositoryMetadata(repositoryMetadata.name(),
                                                                   newRoles,
                                                                   repositoryMetadata.creation(),
                                                                   repositoryMetadata.removal(),
                                                                   repositoryMetadata.status()));
            } else {
                builder.put(entry);
            }
        }
        return builder.build();
    }

    /**
     * Updates a {@link ProjectRole} for the {@link Application} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> updateApplicationRole(Author author, String projectName,
                                                             Application application, ProjectRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(application, "application");
        requireNonNull(role, "role");
        final ApplicationRegistration registration = new ApplicationRegistration(application.appId(), role,
                                                                                 UserAndTimestamp.of(author));
        final JsonPointer path = JsonPointer.compile("/applications" + encodeSegment(registration.id()));
        final Change<JsonNode> change =
                Change.ofJsonPatch(METADATA_JSON,
                                   JsonPatchOperation.replace(
                                           path, Jackson.valueToTree(registration)).toJsonNode());
        final String commitSummary = "Update the role of an application '" + application.appId() +
                                     "' as '" + role + "' for the project '" + projectName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, change);
    }

    /**
     * Adds the {@link RepositoryRole} for the specified {@code member} to the specified {@code repoName}
     * in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> addUserRepositoryRole(Author author, String projectName,
                                                             String repoName, User member,
                                                             RepositoryRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(member, "member");
        requireNonNull(role, "role");

        return getProject(projectName).thenCompose(project -> {
            project.repo(repoName); // Raises an exception if the repository does not exist.
            ensureProjectMember(project, member);
            final String commitSummary = "Add repository role of '" + member.id() +
                                         "' as '" + role + "' to '" + projectName + '/' + repoName + '\n';
            final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                    repoName, (headRevision, repositoryMetadata) -> {
                final Roles roles = repositoryMetadata.roles();
                if (roles.users().get(member.id()) != null) {
                    throw new ChangeConflictException(
                            "the member " + member.id() + " is already added to '" +
                            projectName + '/' + repoName + '\'');
                }

                final Map<String, RepositoryRole> users = roles.users();
                final ImmutableMap<String, RepositoryRole> newUsers = addToMap(users, member.id(), role);
                final Roles newRoles = new Roles(roles.projectRoles(), newUsers, roles.applications());
                return new RepositoryMetadata(repositoryMetadata.name(),
                                              newRoles,
                                              repositoryMetadata.creation(),
                                              repositoryMetadata.removal(),
                                              repositoryMetadata.status());
            });
            return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
        });
    }

    /**
     * Removes the {@link RepositoryRole} for the specified {@code member} from the specified {@code repoName}
     * in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> removeUserRepositoryRole(Author author, String projectName,
                                                                String repoName, User member) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(member, "member");

        final String memberId = member.id();
        final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                repoName, (headRevision, repositoryMetadata) -> {
            final Roles roles = repositoryMetadata.roles();
            if (roles.users().get(memberId) == null) {
                throw new MemberNotFoundException(memberId, projectName, repoName);
            }

            final Map<String, RepositoryRole> newUsers = removeFromMap(roles.users(), memberId);
            final Roles newRoles = new Roles(roles.projectRoles(), newUsers, roles.applications());
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.status());
        });
        final String commitSummary = "Remove repository role of the '" + memberId +
                                     "' from '" + projectName + '/' + repoName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Updates the {@link RepositoryRole} for the specified {@code member} of the specified {@code repoName}
     * in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> updateUserRepositoryRole(Author author, String projectName,
                                                                String repoName, User member,
                                                                RepositoryRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(member, "member");
        requireNonNull(role, "role");

        final String memberId = member.id();
        final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                repoName, (headRevision, repositoryMetadata) -> {
            final Roles roles = repositoryMetadata.roles();
            final RepositoryRole oldRepositoryRole = roles.users().get(memberId);
            if (oldRepositoryRole == null) {
                throw new MemberNotFoundException(memberId, projectName, repoName);
            }

            if (oldRepositoryRole == role) {
                throw new RedundantChangeException(
                        headRevision,
                        "the repository role of " + memberId + " in '" + projectName + '/' + repoName +
                        "' isn't changed.");
            }

            final Map<String, RepositoryRole> newUsers = updateMap(roles.users(), memberId, role);
            final Roles newRoles = new Roles(roles.projectRoles(), newUsers, roles.applications());
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.status());
        });
        final String commitSummary = "Update repository role of the '" + memberId + "' as '" + role +
                                     "' for '" + projectName + '/' + repoName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Adds the {@link RepositoryRole} for the {@link Application} of the specified {@code appId} to the
     * specified {@code repoName} in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> addApplicationRepositoryRole(Author author, String projectName,
                                                                    String repoName, String appId,
                                                                    RepositoryRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(appId, "appId");
        requireNonNull(role, "role");

        return getProject(projectName).thenCompose(project -> {
            project.repo(repoName); // Raises an exception if the repository does not exist.
            ensureProjectApplication(project, appId);
            final String commitSummary = "Add repository role of the application '" + appId + "' as '" + role +
                                         "' to '" + projectName + '/' + repoName + "'\n";
            final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                    repoName, (headRevision, repositoryMetadata) -> {
                final Roles roles = repositoryMetadata.roles();
                if (roles.applications().get(appId) != null) {
                    throw new ChangeConflictException(
                            "the application " + appId + " is already added to '" +
                            projectName + '/' + repoName + '\'');
                }

                final Map<String, RepositoryRole> newApplications = addToMap(roles.applications(), appId, role);
                final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), newApplications);
                return new RepositoryMetadata(repositoryMetadata.name(),
                                              newRoles,
                                              repositoryMetadata.creation(),
                                              repositoryMetadata.removal(),
                                              repositoryMetadata.status());
            });
            return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
        });
    }

    /**
     * Removes the {@link RepositoryRole} for the {@link Application} of the specified {@code appId} of
     * the specified {@code repoName} in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> removeApplicationRepositoryRole(Author author, String projectName,
                                                                       String repoName, String appId) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(appId, "appId");

        final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                repoName, (headRevision, repositoryMetadata) -> {
            final Roles roles = repositoryMetadata.roles();
            if (roles.applications().get(appId) == null) {
                throw new ChangeConflictException(
                        "the application " + appId + " doesn't exist at '" +
                        projectName + '/' + repoName + '\'');
            }

            final Map<String, RepositoryRole> newApplications = removeFromMap(roles.applications(), appId);
            final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), newApplications);
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.status());
        });
        final String commitSummary = "Remove repository role of the application '" + appId +
                                     "' from '" + projectName + '/' + repoName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    // TODO(minwoox): Add this API to MetadataApiService
    /**
     * Updates the {@link RepositoryRole} for the {@link Application} of the specified {@code appId} of
     * the specified {@code repoName} in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> updateApplicationRepositoryRole(Author author, String projectName,
                                                                       String repoName, String appId,
                                                                       RepositoryRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(appId, "appId");
        requireNonNull(role, "role");

        final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                repoName, (headRevision, repositoryMetadata) -> {
            final Roles roles = repositoryMetadata.roles();
            final RepositoryRole oldRepositoryRole = roles.applications().get(appId);
            if (oldRepositoryRole == null) {
                throw new ApplicationNotFoundException(
                        "the application " + appId + " doesn't exist at '" +
                        projectName + '/' + repoName + '\'');
            }

            if (oldRepositoryRole == role) {
                throw new RedundantChangeException(
                        headRevision,
                        "the permission of " + appId + " in '" + projectName + '/' + repoName +
                        "' isn't changed.");
            }

            final Map<String, RepositoryRole> newApplications = updateMap(roles.applications(), appId, role);
            final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), newApplications);
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.status());
        });
        final String commitSummary = "Update repository role of the application '" + appId +
                                     "' for '" + projectName + '/' + repoName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Finds {@link RepositoryRole} of the specified {@link User} or {@link UserWithApplication}
     * from the specified {@code repoName} in the specified {@code projectName}. If the {@link User}
     * is not found, it will return {@code null}.
     */
    public CompletableFuture<RepositoryRole> findRepositoryRole(String projectName, String repoName,
                                                                User user) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(user, "user");
        if (user.isSystemAdmin()) {
            return CompletableFuture.completedFuture(RepositoryRole.ADMIN);
        }
        if (user instanceof UserWithApplication) {
            return findRepositoryRole(projectName, repoName, ((UserWithApplication) user).application());
        }
        return findRepositoryRole0(projectName, repoName, user);
    }

    /**
     * Finds {@link RepositoryRole} of the specified {@link Token} from the specified
     * {@code repoName} in the specified {@code projectName}. If the {@code appId} is not found,
     * it will return {@code null}.
     */
    public CompletableFuture<RepositoryRole> findRepositoryRole(String projectName, String repoName,
                                                                Application application) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(application, "application");

        return getProject(projectName).thenApply(metadata -> {
            final RepositoryMetadata repositoryMetadata = metadata.repo(repoName);
            final Roles roles = repositoryMetadata.roles();
            final String appId = application.appId();
            final RepositoryRole tokenRepositoryRole = roles.applications().get(appId);

            final ApplicationRegistration projectApplicationRegistration = metadata.applications().get(appId);
            final ProjectRole projectRole;
            if (projectApplicationRegistration != null) {
                projectRole = projectApplicationRegistration.role();
            } else {
                // System admin applications were checked before this method.
                assert !application.isSystemAdmin();
                if (application.allowGuestAccess()) {
                    projectRole = ProjectRole.GUEST;
                } else {
                    // The application is not allowed with the GUEST permission.
                    return null;
                }
            }
            return repositoryRole(roles, tokenRepositoryRole, projectRole);
        });
    }

    private CompletableFuture<RepositoryRole> findRepositoryRole0(String projectName, String repoName,
                                                                  User user) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(user, "user");

        return getProject(projectName).thenApply(metadata -> {
            final RepositoryMetadata repositoryMetadata = metadata.repo(repoName);
            final Roles roles = repositoryMetadata.roles();
            final RepositoryRole userRepositoryRole = roles.users().get(user.id());

            final Member projectUser = metadata.memberOrDefault(user.id(), null);
            final ProjectRole projectRole = projectUser != null ? projectUser.role() : ProjectRole.GUEST;
            return repositoryRole(roles, userRepositoryRole, projectRole);
        });
    }

    @Nullable
    private static RepositoryRole repositoryRole(Roles roles, @Nullable RepositoryRole repositoryRole,
                                                 ProjectRole projectRole) {
        if (projectRole == ProjectRole.OWNER) {
            return RepositoryRole.ADMIN;
        }

        final RepositoryRole memberOrGuestRole;
        if (projectRole == ProjectRole.MEMBER) {
            memberOrGuestRole = roles.projectRoles().member();
        } else {
            assert projectRole == ProjectRole.GUEST;
            memberOrGuestRole = roles.projectRoles().guest();
        }

        if (repositoryRole == RepositoryRole.ADMIN || memberOrGuestRole == RepositoryRole.ADMIN) {
            return RepositoryRole.ADMIN;
        }

        if (repositoryRole == RepositoryRole.WRITE || memberOrGuestRole == RepositoryRole.WRITE) {
            return RepositoryRole.WRITE;
        }

        if (repositoryRole == RepositoryRole.READ || memberOrGuestRole == RepositoryRole.READ) {
            return RepositoryRole.READ;
        }

        return null;
    }

    /**
     * Finds a {@link ProjectRole} of the specified {@link User} in the specified {@code projectName}.
     */
    public CompletableFuture<ProjectRole> findProjectRole(String projectName, User user) {
        requireNonNull(projectName, "projectName");
        requireNonNull(user, "user");

        if (user.isSystemAdmin()) {
            return CompletableFuture.completedFuture(ProjectRole.OWNER);
        }
        return getProject(projectName).thenApply(project -> {
            if (user instanceof UserWithApplication) {
                final ApplicationRegistration registration = project.applications().getOrDefault(
                        user.login(), null); // login is appId for UserWithApplication
                //noinspection ConstantValue
                return registration != null ? registration.role() : ProjectRole.GUEST;
            } else {
                final Member member = project.memberOrDefault(user.id(), null);
                return member != null ? member.role() : ProjectRole.GUEST;
            }
        });
    }

    /**
     * Fetches the {@link ApplicationRegistry} from the repository.
     */
    public CompletableFuture<ApplicationRegistry> fetchApplicationRegistry() {
        return applicationService.fetchApplicationRegistry();
    }

    /**
     * Returns an {@link ApplicationRegistry}.
     */
    public ApplicationRegistry getApplicationRegistry() {
        return applicationService.getApplicationRegistry();
    }

    /**
     * Creates a new user-level {@link Token} with the specified {@code appId}. A secret for the {@code appId}
     * will be automatically generated.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId) {
        return applicationService.createToken(author, appId);
    }

    /**
     * Creates a new {@link Token} with the specified {@code appId}, {@code isSystemAdmin} and an auto-generated
     * secret.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId, boolean isSystemAdmin) {
        return applicationService.createToken(author, appId, isSystemAdmin);
    }

    /**
     * Creates a new user-level {@link Token} with the specified {@code appId} and {@code secret}.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId, String secret) {
        return applicationService.createToken(author, appId, secret);
    }

    /**
     * Creates a new {@link Token} with the specified {@code appId}, {@code secret} and {@code isSystemAdmin}.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId, String secret,
                                                   boolean isSystemAdmin) {
        return applicationService.createToken(author, appId, secret, isSystemAdmin);
    }

    /**
     * Removes the {@link Token} of the specified {@code appId}.
     * This sets {@link Application#deletion()} to the current timestamp. It will be purged later by
     * {@link #purgeApplication(Author, String)}.
     */
    public CompletableFuture<Revision> destroyToken(Author author, String appId) {
        return applicationService.destroyToken(author, appId);
    }

    /**
     * Purges the {@link Application} of the specified {@code appId} that was removed before.
     *
     * <p>Note that this is a blocking method that should not be invoked in an event loop.
     */
    public Revision purgeApplication(Author author, String appId) {
        purgeApplicationInProjects(author, appId);
        return applicationService.purgeApplication(author, appId);
    }

    private void purgeApplicationInProjects(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final Collection<Project> projects = listProjectsWithoutInternal(projectManager.list(),
                                                                         User.SYSTEM_ADMIN).values();
        // Remove the application from projects that only have the application.
        for (Project project : projects) {
            // Fetch the metadata to get the latest information.
            final ProjectMetadata projectMetadata = fetchMetadata(project.name()).join();
            final boolean containsTargetApplicationInTheProject =
                    projectMetadata.applications().values()
                                   .stream()
                                   .anyMatch(application -> application.appId().equals(appId));

            if (containsTargetApplicationInTheProject) {
                removeApplicationFromProject(author, project.name(), appId, true).join();
            }
        }
    }

    /**
     * Activates the {@link Token} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> activateToken(Author author, String appId) {
        return applicationService.activateToken(author, appId);
    }

    /**
     * Deactivates the {@link Token} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> deactivateToken(Author author, String appId) {
        return applicationService.deactivateToken(author, appId);
    }

    /**
     * Returns an {@link Application} which has the specified {@code appId}.
     */
    public Application findApplicationByAppId(String appId) {
        return applicationService.findApplicationByAppId(appId);
    }

    /**
     * Returns a {@link ApplicationCertificate} which has the specified {@code certificateId}.
     */
    public ApplicationCertificate findCertificateById(String certificateId) {
        return applicationService.findCertificateById(certificateId);
    }

    /**
     * Returns a {@link Token} which has the specified {@code secret}.
     */
    public Token findTokenBySecret(String secret) {
        return applicationService.findTokenBySecret(secret);
    }

    /**
     * Ensures that the specified {@code user} is a member of the specified {@code project}.
     */
    private static void ensureProjectMember(ProjectMetadata project, User user) {
        requireNonNull(project, "project");
        requireNonNull(user, "user");

        if (project.members().values().stream().noneMatch(member -> member.login().equals(user.id()))) {
            throw new MemberNotFoundException(user.id(), project.name());
        }
    }

    /**
     * Ensures that the specified {@code appId} is an application of the specified {@code project}.
     */
    private static void ensureProjectApplication(ProjectMetadata project, String appId) {
        requireNonNull(project, "project");
        requireNonNull(appId, "appId");

        if (!project.applications().containsKey(appId)) {
            throw new ApplicationNotFoundException(
                    appId + " is not an application of the project '" + project.name() + '\'');
        }
    }

    static <T> ImmutableMap<String, T> addToMap(Map<String, T> map, String key, T value) {
        return ImmutableMap.<String, T>builderWithExpectedSize(map.size() + 1)
                           .putAll(map)
                           .put(key, value)
                           .build();
    }

    static <T> Map<String, T> updateMap(Map<String, T> map, String key, T value) {
        final ImmutableMap.Builder<String, T> builder = ImmutableMap.builderWithExpectedSize(map.size());
        for (Entry<String, T> entry : map.entrySet()) {
            if (entry.getKey().equals(key)) {
                builder.put(key, value);
            } else {
                builder.put(entry);
            }
        }
        return builder.build();
    }

    static <T> ImmutableMap<String, T> removeFromMap(Map<String, T> map, String id) {
        return map.entrySet().stream()
                  .filter(e -> !e.getKey().equals(id))
                  .collect(toImmutableMap(Entry::getKey, Entry::getValue));
    }

    /**
     * Updates the {@link ServerStatus} of the specified {@code repoName}.
     */
    public CompletableFuture<Revision> updateRepositoryStatus(
            Author author, String projectName, String repoName, RepositoryStatus repositoryStatus) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(repositoryStatus, "repositoryStatus");
        final String newRepoName;
        if (Project.REPO_META.equals(repoName)) {
            newRepoName = Project.REPO_DOGMA; // Use dogma repository because meta repository will be removed.
        } else {
            newRepoName = repoName;
        }

        final ProjectMetadataTransformer transformer;
        if (Project.REPO_DOGMA.equals(newRepoName)) {
            // Have to use ProjectMetadataTransformer because the repository metadata of dogma repository
            // might not exist.
            transformer = new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                final RepositoryMetadata repositoryMetadata = projectMetadata.repos().get(Project.REPO_DOGMA);
                if (repositoryMetadata != null) {
                    throwIfRedundant(repositoryStatus, headRevision, repositoryMetadata, Project.REPO_DOGMA);
                }
                final RepositoryMetadata newRepositoryMetadata = RepositoryMetadata.ofDogma(repositoryStatus);
                final Builder<String, RepositoryMetadata> builder = ImmutableMap.builder();
                builder.put(Project.REPO_DOGMA, newRepositoryMetadata);
                projectMetadata.repos().forEach((name, metadata) -> {
                    if (!Project.REPO_DOGMA.equals(name)) {
                        builder.put(name, metadata);
                    }
                });
                return new ProjectMetadata(projectMetadata.name(),
                                           builder.build(),
                                           projectMetadata.members(),
                                           projectMetadata.applications(),
                                           projectMetadata.creation(),
                                           projectMetadata.removal());
            });
        } else {
            transformer = new RepositoryMetadataTransformer(
                    newRepoName, (headRevision, repositoryMetadata) -> {
                throwIfRedundant(repositoryStatus, headRevision, repositoryMetadata, newRepoName);

                return new RepositoryMetadata(repositoryMetadata.name(),
                                              repositoryMetadata.roles(),
                                              repositoryMetadata.creation(),
                                              repositoryMetadata.removal(),
                                              repositoryStatus);
            });
        }

        final String commitSummary = "Update the status of '" + projectName + '/' + newRepoName +
                                     "'. status: " + repositoryStatus;
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer, true);
    }

    private static void throwIfRedundant(RepositoryStatus repositoryStatus, Revision headRevision,
                                         RepositoryMetadata repositoryMetadata, String newRepoName) {
        if (repositoryMetadata.status() == repositoryStatus) {
            throw new RedundantChangeException(
                    headRevision,
                    "the status of '" + newRepoName + "' isn't changed. status: " + repositoryStatus);
        }
    }

    /**
     * Creates a new application {@link ApplicationCertificate} with the specified {@code appId} and
     * {@code certificateId}.
     */
    public CompletableFuture<Revision> createCertificate(Author author, String appId, String certificateId,
                                                         boolean isSystemAdmin) {
        return applicationService.createCertificate(author, appId, certificateId, isSystemAdmin);
    }

    /**
     * Removes the {@link ApplicationCertificate} of the specified {@code appId}.
     * This sets {@link Application#deletion()} to the current timestamp. It will be purged later by
     * {@link #purgeApplication(Author, String)}.
     */
    public CompletableFuture<Revision> destroyCertificate(Author author, String appId) {
        return applicationService.destroyCertificate(author, appId);
    }

    /**
     * Activates the {@link ApplicationCertificate} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> activateCertificate(Author author, String appId) {
        return applicationService.activateCertificate(author, appId);
    }

    /**
     * Deactivates the {@link ApplicationCertificate} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> deactivateCertificate(Author author, String appId) {
        return applicationService.deactivateCertificate(author, appId);
    }

    /**
     * Updates the application level of the specified {@code appId}.
     */
    public CompletableFuture<Revision> updateApplicationLevel(Author author, String appId,
                                                              boolean toBeSystemAdmin) {
        return applicationService.updateApplicationLevel(author, appId, toBeSystemAdmin);
    }
}
