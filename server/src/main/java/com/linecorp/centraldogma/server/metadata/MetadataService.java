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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.RepositoryStatus;
import com.linecorp.centraldogma.common.Revision;
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
    // TODO(minwoox): Rename to /app-identity-registry.json
    public static final String TOKEN_JSON = "/tokens.json";

    private final ProjectManager projectManager;
    private final RepositorySupport<ProjectMetadata> metadataRepo;
    private final AppIdentityService appIdentityService;

    private final Map<String, CompletableFuture<Revision>> reposInAddingMetadata = new ConcurrentHashMap<>();

    /**
     * Creates a new instance.
     */
    public MetadataService(ProjectManager projectManager, CommandExecutor executor,
                           InternalProjectInitializer projectInitializer) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
        metadataRepo = new RepositorySupport<>(projectManager, executor, ProjectMetadata.class);
        appIdentityService = new AppIdentityService(projectManager, executor, projectInitializer);
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

        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    if (projectMetadata.removal() != null) {
                        throw new ChangeConflictException("Project is already removed: " + projectName);
                    }
                    return new ProjectMetadata(projectMetadata.name(),
                                               projectMetadata.repos(),
                                               projectMetadata.members(),
                                               null,
                                               projectMetadata.appIds(),
                                               projectMetadata.creation(),
                                               UserAndTimestamp.of(author));
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author,
                                 "Remove the project: " + projectName, transformer);
    }

    /**
     * Restores a {@link ProjectMetadata} whose name equals to the specified {@code projectName}.
     */
    public CompletableFuture<Revision> restoreProject(Author author, String projectName) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");

        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    if (projectMetadata.removal() == null) {
                        throw new ChangeConflictException("Project is not removed: " + projectName);
                    }
                    return new ProjectMetadata(projectMetadata.name(),
                                               projectMetadata.repos(),
                                               projectMetadata.members(),
                                               null,
                                               projectMetadata.appIds(),
                                               projectMetadata.creation(),
                                               null);
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author,
                                 "Restore the project: " + projectName, transformer);
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
        final String commitSummary =
                "Add a member '" + newMember.id() + "' to the project '" + projectName + '\'';

        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    if (projectMetadata.members().containsKey(newMember.id())) {
                        throw new ChangeConflictException("Member already exists: " + newMember.id());
                    }
                    final ImmutableMap<String, Member> newMembers =
                            ImmutableMap.<String, Member>builderWithExpectedSize(
                                                projectMetadata.members().size() + 1)
                                        .putAll(projectMetadata.members())
                                        .put(newMember.id(), newMember)
                                        .build();
                    return new ProjectMetadata(projectMetadata.name(),
                                               projectMetadata.repos(),
                                               newMembers,
                                               null,
                                               projectMetadata.appIds(),
                                               projectMetadata.creation(),
                                               projectMetadata.removal());
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
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
                                               null,
                                               projectMetadata.appIds(),
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
                final Roles newRoles = new Roles(roles.projectRoles(), newUsers, null, roles.appIds());
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

        final String commitSummary = "Updates the role of the member '" + member.id() +
                                     "' as '" + projectRole + "' for the project '" + projectName + '\'';

        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    final Member existingMember = projectMetadata.members().get(member.id());
                    if (existingMember == null) {
                        throw new EntryNotFoundException("Member not found: " + member.id());
                    }
                    if (existingMember.role() == projectRole) {
                        throw new RedundantChangeException("Member already has role: " + projectRole);
                    }
                    final Member updatedMember = new Member(existingMember.login(), projectRole,
                                                            existingMember.creation());
                    final ImmutableMap<String, Member> newMembers =
                            ImmutableMap.<String, Member>builderWithExpectedSize(
                                                projectMetadata.members().size())
                                        .putAll(projectMetadata.members())
                                        .put(member.id(), updatedMember)
                                        .buildKeepingLast();
                    return new ProjectMetadata(projectMetadata.name(),
                                               projectMetadata.repos(),
                                               newMembers,
                                               null,
                                               projectMetadata.appIds(),
                                               projectMetadata.creation(),
                                               projectMetadata.removal());
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
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

        final String commitSummary =
                "Add a repo '" + repositoryMetadata.id() + "' to the project '" + projectName + '\'';

        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    if (projectMetadata.repos().containsKey(repoName)) {
                        throw RepositoryExistsException.of(projectName, repoName);
                    }
                    final ImmutableMap<String, RepositoryMetadata> newRepos =
                            ImmutableMap.<String, RepositoryMetadata>builderWithExpectedSize(
                                                projectMetadata.repos().size() + 1)
                                        .putAll(projectMetadata.repos())
                                        .put(repoName, repositoryMetadata)
                                        .build();
                    return new ProjectMetadata(projectMetadata.name(),
                                               newRepos,
                                               projectMetadata.members(),
                                               null,
                                               projectMetadata.appIds(),
                                               projectMetadata.creation(),
                                               projectMetadata.removal());
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Removes a {@link RepositoryMetadata} of the specified {@code repoName} from the specified
     * {@code projectName}.
     */
    public CompletableFuture<Revision> removeRepo(Author author, String projectName, String repoName) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        final String commitSummary =
                "Remove the repo '" + repoName + "' from the project '" + projectName + '\'';

        final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                repoName, (headRevision, repositoryMetadata) -> {
            if (repositoryMetadata.removal() != null) {
                throw new ChangeConflictException("Repository is already removed: " +
                                                  projectName + '/' + repoName);
            }
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          repositoryMetadata.roles(),
                                          repositoryMetadata.creation(),
                                          UserAndTimestamp.of(author),
                                          repositoryMetadata.status());
        });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Purges a {@link RepositoryMetadata} of the specified {@code repoName} from the specified
     * {@code projectName}.
     */
    public CompletableFuture<Revision> purgeRepo(Author author, String projectName, String repoName) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        final String commitSummary =
                "Purge the repo '" + repoName + "' from the project '" + projectName + '\'';

        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    if (!projectMetadata.repos().containsKey(repoName)) {
                        throw new EntryNotFoundException("Repository not found: " +
                                                         projectName + '/' + repoName);
                    }
                    final Map<String, RepositoryMetadata> newRepos =
                            removeFromMap(projectMetadata.repos(), repoName);
                    return new ProjectMetadata(projectMetadata.name(),
                                               newRepos,
                                               projectMetadata.members(),
                                               null,
                                               projectMetadata.appIds(),
                                               projectMetadata.creation(),
                                               projectMetadata.removal());
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Restores a {@link RepositoryMetadata} of the specified {@code repoName} in the specified
     * {@code projectName}.
     */
    public CompletableFuture<Revision> restoreRepo(Author author, String projectName, String repoName) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        final String commitSummary =
                "Restore the repo '" + repoName + "' from the project '" + projectName + '\'';

        final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                repoName, (headRevision, repositoryMetadata) -> {
            if (repositoryMetadata.removal() == null) {
                throw new ChangeConflictException("Repository is not removed: " + projectName + '/' + repoName);
            }
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          repositoryMetadata.roles(),
                                          repositoryMetadata.creation(),
                                          null,
                                          repositoryMetadata.status());
        });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
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
            final Roles newRoles = new Roles(projectRoles, repositoryMetadata.roles().users(), null,
                                             repositoryMetadata.roles().appIds());
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.status());
        });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Adds an {@link AppIdentity} of the specified {@code appId} to the specified {@code projectName}.
     */
    public CompletableFuture<Revision> addAppIdentity(Author author, String projectName,
                                                      String appId, ProjectRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(appId, "appId");
        requireNonNull(role, "role");
        appIdentityService.getAppIdentityRegistry().get(appId); // Will raise an exception if not found.
        final AppIdentityRegistration registration = new AppIdentityRegistration(appId, role,
                                                                                 UserAndTimestamp.of(author));
        final String commitSummary = "Add an app identity '" + registration.id() +
                                     "' to the project '" + projectName + "' with a role '" + role + '\'';

        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    if (projectMetadata.appIds().containsKey(registration.id())) {
                        throw new ChangeConflictException("Token already exists: " + registration.id());
                    }
                    final ImmutableMap<String, AppIdentityRegistration> newAppIds =
                            ImmutableMap.<String, AppIdentityRegistration>builderWithExpectedSize(
                                                projectMetadata.appIds().size() + 1)
                                        .putAll(projectMetadata.appIds())
                                        .put(registration.id(), registration)
                                        .build();
                    return new ProjectMetadata(projectMetadata.name(),
                                               projectMetadata.repos(),
                                               projectMetadata.members(),
                                               null,
                                               newAppIds,
                                               projectMetadata.creation(),
                                               projectMetadata.removal());
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Removes the {@link AppIdentity} of the specified {@code appId} from the specified {@code projectName}.
     * It also removes every app identity repository role belonging to {@link AppIdentity} from
     * every {@link RepositoryMetadata}.
     */
    public CompletableFuture<Revision> removeAppIdentityFromProject(Author author,
                                                                    String projectName, String appId) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(appId, "appId");

        return removeAppIdentityFromProject(author, projectName, appId, false);
    }

    CompletableFuture<Revision> removeAppIdentityFromProject(Author author, String projectName, String appId,
                                                             boolean quiet) {
        final String commitSummary =
                "Remove the app identity '" + appId + "' from the project '" + projectName + '\'';
        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    final Map<String, AppIdentityRegistration> appIds = projectMetadata.appIds();
                    final Map<String, AppIdentityRegistration> newAppIds;
                    if (appIds.get(appId) == null) {
                        if (!quiet) {
                            throw new AppIdentityNotFoundException(
                                    "failed to find the app identity " + appId + " in project " + projectName);
                        }
                        newAppIds = appIds;
                    } else {
                        newAppIds = appIds.entrySet()
                                          .stream()
                                          .filter(entry -> !entry.getKey().equals(appId))
                                          .collect(toImmutableMap(Entry::getKey, Entry::getValue));
                    }

                    final ImmutableMap<String, RepositoryMetadata> newRepos =
                            removeAppIdentityFromRepositories(appId, projectMetadata);
                    return new ProjectMetadata(projectMetadata.name(),
                                               newRepos,
                                               projectMetadata.members(),
                                               null,
                                               newAppIds,
                                               projectMetadata.creation(),
                                               projectMetadata.removal());
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    private static ImmutableMap<String, RepositoryMetadata> removeAppIdentityFromRepositories(
            String appId, ProjectMetadata projectMetadata) {
        final ImmutableMap.Builder<String, RepositoryMetadata> builder =
                ImmutableMap.builderWithExpectedSize(projectMetadata.repos().size());
        for (Entry<String, RepositoryMetadata> entry : projectMetadata.repos().entrySet()) {
            final RepositoryMetadata repositoryMetadata = entry.getValue();
            final Roles roles = repositoryMetadata.roles();
            if (roles.appIds().get(appId) != null) {
                final Map<String, RepositoryRole> newAppIds = removeFromMap(roles.appIds(), appId);
                final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), null, newAppIds);
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
     * Updates a {@link ProjectRole} for the {@link AppIdentity} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> updateAppIdentityRole(
            Author author, String projectName,
            AppIdentity appIdentity, ProjectRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(appIdentity, "appIdentity");
        requireNonNull(role, "role");
        final AppIdentityRegistration registration = new AppIdentityRegistration(appIdentity.appId(), role,
                                                                                 UserAndTimestamp.of(author));
        final String commitSummary = "Update the role of an app identity '" + appIdentity.appId() +
                                     "' as '" + role + "' for the project '" + projectName + '\'';

        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    final AppIdentityRegistration existingAppIdentity =
                            projectMetadata.appIds().get(registration.appId());
                    if (existingAppIdentity == null) {
                        throw new EntryNotFoundException("App identity not found: " + registration.appId());
                    }
                    if (existingAppIdentity.role() == role) {
                        throw new RedundantChangeException("App identity already has role: " + role);
                    }
                    final ImmutableMap<String, AppIdentityRegistration> newAppIds =
                            ImmutableMap.<String, AppIdentityRegistration>builderWithExpectedSize(
                                                projectMetadata.appIds().size())
                                        .putAll(projectMetadata.appIds())
                                        .put(registration.appId(), registration)
                                        .buildKeepingLast();
                    return new ProjectMetadata(projectMetadata.name(),
                                               projectMetadata.repos(),
                                               projectMetadata.members(),
                                               null,
                                               newAppIds,
                                               projectMetadata.creation(),
                                               projectMetadata.removal());
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
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
                final Roles newRoles = new Roles(roles.projectRoles(), newUsers, null, roles.appIds());
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
            final Roles newRoles = new Roles(roles.projectRoles(), newUsers, null, roles.appIds());
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
            final Roles newRoles = new Roles(roles.projectRoles(), newUsers, null, roles.appIds());
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
     * Adds the {@link RepositoryRole} for the {@link AppIdentity} of the specified {@code appId} to the
     * specified {@code repoName} in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> addAppIdentityRepositoryRole(Author author, String projectName,
                                                                    String repoName, String appId,
                                                                    RepositoryRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(appId, "appId");
        requireNonNull(role, "role");

        return getProject(projectName).thenCompose(project -> {
            project.repo(repoName); // Raises an exception if the repository does not exist.
            ensureProjectAppIdentity(project, appId);
            final String commitSummary = "Add repository role of the app identity '" + appId + "' as '" + role +
                                         "' to '" + projectName + '/' + repoName + "'\n";
            final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                    repoName, (headRevision, repositoryMetadata) -> {
                final Roles roles = repositoryMetadata.roles();
                if (roles.appIds().get(appId) != null) {
                    throw new ChangeConflictException(
                            "the app identity " + appId + " is already added to '" +
                            projectName + '/' + repoName + '\'');
                }

                final Map<String, RepositoryRole> newAppIds = addToMap(roles.appIds(), appId, role);
                final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), null, newAppIds);
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
     * Removes the {@link RepositoryRole} for the {@link AppIdentity} of the specified {@code appId} of
     * the specified {@code repoName} in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> removeAppIdentityRepositoryRole(Author author, String projectName,
                                                                       String repoName, String appId) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(appId, "appId");

        final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                repoName, (headRevision, repositoryMetadata) -> {
            final Roles roles = repositoryMetadata.roles();
            if (roles.appIds().get(appId) == null) {
                throw new ChangeConflictException(
                        "the app identity " + appId + " doesn't exist at '" +
                        projectName + '/' + repoName + '\'');
            }

            final Map<String, RepositoryRole> newAppIds = removeFromMap(roles.appIds(), appId);
            final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), null, newAppIds);
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.status());
        });
        final String commitSummary = "Remove repository role of the app identity '" + appId +
                                     "' from '" + projectName + '/' + repoName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    // TODO(minwoox): Add this API to MetadataApiService
    /**
     * Updates the {@link RepositoryRole} for the {@link AppIdentity} of the specified {@code appId} of
     * the specified {@code repoName} in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> updateAppIdentityRepositoryRole(Author author, String projectName,
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
            final RepositoryRole oldRepositoryRole = roles.appIds().get(appId);
            if (oldRepositoryRole == null) {
                throw new AppIdentityNotFoundException(
                        "the app identity " + appId + " doesn't exist at '" +
                        projectName + '/' + repoName + '\'');
            }

            if (oldRepositoryRole == role) {
                throw new RedundantChangeException(
                        headRevision,
                        "the permission of " + appId + " in '" + projectName + '/' + repoName +
                        "' isn't changed.");
            }

            final Map<String, RepositoryRole> newAppIds = updateMap(roles.appIds(), appId, role);
            final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), null, newAppIds);
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.status());
        });
        final String commitSummary = "Update repository role of the app identity '" + appId +
                                     "' for '" + projectName + '/' + repoName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Finds {@link RepositoryRole} of the specified {@link User} or {@link UserWithAppIdentity}
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
        if (user instanceof UserWithAppIdentity) {
            return findRepositoryRole(projectName, repoName, ((UserWithAppIdentity) user).appIdentity());
        }
        return findRepositoryRole0(projectName, repoName, user);
    }

    /**
     * Finds {@link RepositoryRole} of the specified {@link Token} from the specified
     * {@code repoName} in the specified {@code projectName}. If the {@code appId} is not found,
     * it will return {@code null}.
     */
    public CompletableFuture<RepositoryRole> findRepositoryRole(String projectName, String repoName,
                                                                AppIdentity appIdentity) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(appIdentity, "appIdentity");

        return getProject(projectName).thenApply(metadata -> {
            final RepositoryMetadata repositoryMetadata = metadata.repo(repoName);
            final Roles roles = repositoryMetadata.roles();
            final String appId = appIdentity.appId();
            final RepositoryRole repositoryRole = roles.appIds().get(appId);

            final AppIdentityRegistration projectAppIdentityRegistration = metadata.appIds().get(appId);
            final ProjectRole projectRole;
            if (projectAppIdentityRegistration != null) {
                projectRole = projectAppIdentityRegistration.role();
            } else {
                // System admin app identities were checked before this method.
                assert !appIdentity.isSystemAdmin();
                if (appIdentity.allowGuestAccess()) {
                    projectRole = ProjectRole.GUEST;
                } else {
                    // The app identity is not allowed with the GUEST permission.
                    return null;
                }
            }
            return repositoryRole(roles, repositoryRole, projectRole);
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
            if (user instanceof UserWithAppIdentity) {
                final AppIdentityRegistration registration = project.appIds().getOrDefault(
                        user.login(), null); // login is appId for UserWithappIdentity
                //noinspection ConstantValue
                return registration != null ? registration.role() : ProjectRole.GUEST;
            } else {
                final Member member = project.memberOrDefault(user.id(), null);
                return member != null ? member.role() : ProjectRole.GUEST;
            }
        });
    }

    /**
     * Fetches the {@link AppIdentityRegistry} from the repository.
     */
    public CompletableFuture<AppIdentityRegistry> fetchAppIdentityRegistry() {
        return appIdentityService.fetchAppIdentityRegistry();
    }

    /**
     * Returns an {@link AppIdentityRegistry}.
     */
    public AppIdentityRegistry getAppIdentityRegistry() {
        return appIdentityService.getAppIdentityRegistry();
    }

    /**
     * Creates a new user-level {@link Token} with the specified {@code appId}. A secret for the {@code appId}
     * will be automatically generated.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId) {
        return appIdentityService.createToken(author, appId);
    }

    /**
     * Creates a new {@link Token} with the specified {@code appId}, {@code isSystemAdmin} and an auto-generated
     * secret.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId, boolean isSystemAdmin) {
        return appIdentityService.createToken(author, appId, isSystemAdmin);
    }

    /**
     * Creates a new user-level {@link Token} with the specified {@code appId} and {@code secret}.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId, String secret) {
        return appIdentityService.createToken(author, appId, secret);
    }

    /**
     * Creates a new {@link Token} with the specified {@code appId}, {@code secret} and {@code isSystemAdmin}.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId, String secret,
                                                   boolean isSystemAdmin) {
        return appIdentityService.createToken(author, appId, secret, isSystemAdmin);
    }

    /**
     * Removes the {@link Token} of the specified {@code appId}.
     * This sets {@link AppIdentity#deletion()} to the current timestamp. It will be purged later by
     * {@link #purgeAppIdentity(Author, String)}.
     */
    public CompletableFuture<Revision> destroyToken(Author author, String appId) {
        return appIdentityService.destroyToken(author, appId);
    }

    /**
     * Purges the {@link AppIdentity} of the specified {@code appId} that was removed before.
     *
     * <p>Note that this is a blocking method that should not be invoked in an event loop.
     */
    public Revision purgeAppIdentity(Author author, String appId) {
        purgeAppIdentityInProjects(author, appId);
        return appIdentityService.purgeAppIdentity(author, appId);
    }

    private void purgeAppIdentityInProjects(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final Collection<Project> projects = listProjectsWithoutInternal(projectManager.list(),
                                                                         User.SYSTEM_ADMIN).values();
        // Remove the app identity from projects that only have the app identity.
        for (Project project : projects) {
            // Fetch the metadata to get the latest information.
            final ProjectMetadata projectMetadata = fetchMetadata(project.name()).join();
            final boolean containsTargetAppIdentityInTheProject =
                    projectMetadata.appIds().values()
                                   .stream()
                                   .anyMatch(appIdentity -> appIdentity.appId().equals(appId));

            if (containsTargetAppIdentityInTheProject) {
                removeAppIdentityFromProject(author, project.name(), appId, true).join();
            }
        }
    }

    /**
     * Activates the {@link Token} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> activateToken(Author author, String appId) {
        return appIdentityService.activateToken(author, appId);
    }

    /**
     * Deactivates the {@link Token} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> deactivateToken(Author author, String appId) {
        return appIdentityService.deactivateToken(author, appId);
    }

    /**
     * Returns an {@link AppIdentity} which has the specified {@code appId}.
     */
    public AppIdentity findAppIdentity(String appId) {
        return appIdentityService.findAppIdentity(appId);
    }

    /**
     * Returns a {@link CertificateAppIdentity} which has the specified {@code certificateId}.
     */
    public CertificateAppIdentity findCertificateById(String certificateId) {
        return appIdentityService.findCertificateById(certificateId);
    }

    /**
     * Returns a {@link Token} which has the specified {@code secret}.
     */
    public Token findTokenBySecret(String secret) {
        return appIdentityService.findTokenBySecret(secret);
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
     * Ensures that the specified {@code appId} is an app identity of the specified {@code project}.
     */
    private static void ensureProjectAppIdentity(ProjectMetadata project, String appId) {
        requireNonNull(project, "project");
        requireNonNull(appId, "appId");

        if (!project.appIds().containsKey(appId)) {
            throw new AppIdentityNotFoundException(
                    appId + " is not an app identity of the project '" + project.name() + '\'');
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
                                           null,
                                           projectMetadata.appIds(),
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
     * Creates a new app identity {@link CertificateAppIdentity} with the specified {@code appId} and
     * {@code certificateId}.
     */
    public CompletableFuture<Revision> createCertificate(Author author, String appId, String certificateId,
                                                         boolean isSystemAdmin) {
        return appIdentityService.createCertificate(author, appId, certificateId, isSystemAdmin);
    }

    /**
     * Removes the {@link CertificateAppIdentity} of the specified {@code appId}.
     * This sets {@link AppIdentity#deletion()} to the current timestamp. It will be purged later by
     * {@link #purgeAppIdentity(Author, String)}.
     */
    public CompletableFuture<Revision> destroyCertificate(Author author, String appId) {
        return appIdentityService.destroyCertificate(author, appId);
    }

    /**
     * Activates the {@link CertificateAppIdentity} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> activateCertificate(Author author, String appId) {
        return appIdentityService.activateCertificate(author, appId);
    }

    /**
     * Deactivates the {@link CertificateAppIdentity} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> deactivateCertificate(Author author, String appId) {
        return appIdentityService.deactivateCertificate(author, appId);
    }

    /**
     * Updates the app identity level of the specified {@code appId}.
     */
    public CompletableFuture<Revision> updateAppIdentityLevel(Author author, String appId,
                                                              boolean toBeSystemAdmin) {
        return appIdentityService.updateAppIdentityLevel(author, appId, toBeSystemAdmin);
    }
}
