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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.centraldogma.internal.jsonpatch.JsonPatchOperation.asJsonArray;
import static com.linecorp.centraldogma.internal.jsonpatch.JsonPatchUtil.encodeSegment;
import static com.linecorp.centraldogma.server.internal.storage.project.ProjectApiManager.listProjectsWithoutInternal;
import static com.linecorp.centraldogma.server.metadata.RepositoryMetadata.DEFAULT_PROJECT_ROLES;
import static com.linecorp.centraldogma.server.metadata.RepositorySupport.convertWithJackson;
import static com.linecorp.centraldogma.server.metadata.Tokens.SECRET_PREFIX;
import static com.linecorp.centraldogma.server.metadata.Tokens.validateSecret;
import static com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer.INTERNAL_PROJECT_DOGMA;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.jsonpatch.AddOperation;
import com.linecorp.centraldogma.internal.jsonpatch.RemoveOperation;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceOperation;
import com.linecorp.centraldogma.internal.jsonpatch.TestAbsenceOperation;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.command.CommandExecutor;
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
    public static final String TOKEN_JSON = "/tokens.json";

    /**
     * A {@link JsonPointer} of project removal information.
     */
    private static final JsonPointer PROJECT_REMOVAL = JsonPointer.compile("/removal");

    private final ProjectManager projectManager;
    private final RepositorySupport<ProjectMetadata> metadataRepo;
    private final RepositorySupport<Tokens> tokenRepo;
    private final CommandExecutor executor;

    private final Map<String, CompletableFuture<Revision>> reposInAddingMetadata = new ConcurrentHashMap<>();

    /**
     * Creates a new instance.
     */
    public MetadataService(ProjectManager projectManager, CommandExecutor executor) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.executor = requireNonNull(executor, "executor");
        metadataRepo = new RepositorySupport<>(projectManager, executor,
                                               entry -> convertWithJackson(entry, ProjectMetadata.class));
        tokenRepo = new RepositorySupport<>(projectManager, executor,
                                            entry -> convertWithJackson(entry, Tokens.class));
    }

    /**
     * Returns a {@link ProjectMetadata} whose name equals to the specified {@code projectName}.
     */
    public CompletableFuture<ProjectMetadata> getProject(String projectName) {
        requireNonNull(projectName, "projectName");
        return fetchMetadata(projectName).thenApply(HolderWithRevision::object);
    }

    private CompletableFuture<HolderWithRevision<ProjectMetadata>> fetchMetadata(String projectName) {
        return fetchMetadata0(projectName).thenCompose(holder -> {
            final Set<String> repos = projectManager.get(projectName).repos().list().keySet();
            final Set<String> reposWithMetadata = holder.object().repos().keySet();

            // Make sure all repositories have metadata. If not, create missing metadata.
            // A repository can have missing metadata when a dev forgot to call `addRepo()`
            // after creating a new repository.
            final ImmutableList.Builder<CompletableFuture<Revision>> builder = ImmutableList.builder();
            for (String repo : repos) {
                if (reposWithMetadata.contains(repo) ||
                    repo.equals(Project.REPO_DOGMA)) {
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
                return CompletableFuture.completedFuture(holder);
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
                return fetchMetadata0(projectName);
            });
        });
    }

    private CompletableFuture<HolderWithRevision<ProjectMetadata>> fetchMetadata0(String projectName) {
        return metadataRepo.fetch(projectName, Project.REPO_DOGMA, METADATA_JSON);
    }

    /**
     * Removes a {@link ProjectMetadata} whose name equals to the specified {@code projectName}.
     */
    public CompletableFuture<Revision> removeProject(Author author, String projectName) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");

        final Change<JsonNode> change = Change.ofJsonPatch(
                METADATA_JSON,
                asJsonArray(new TestAbsenceOperation(PROJECT_REMOVAL),
                            new AddOperation(PROJECT_REMOVAL,
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
                Change.ofJsonPatch(METADATA_JSON, new RemoveOperation(PROJECT_REMOVAL).toJsonNode());
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
                                   asJsonArray(new TestAbsenceOperation(path),
                                               new AddOperation(path, Jackson.valueToTree(newMember))));
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
                                               projectMetadata.tokens(),
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
                final Roles newRoles = new Roles(roles.projectRoles(), newUsers, roles.tokens());
                reposBuilder.put(entry.getKey(),
                                 new RepositoryMetadata(repositoryMetadata.name(),
                                                        newRoles,
                                                        repositoryMetadata.creation(),
                                                        repositoryMetadata.removal(),
                                                        repositoryMetadata.writeQuota()));
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
                new ReplaceOperation(JsonPointer.compile("/members" + encodeSegment(member.id()) + "/role"),
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
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        final JsonPointer path = JsonPointer.compile("/repos" + encodeSegment(repoName));
        final RepositoryMetadata newRepositoryMetadata =
                RepositoryMetadata.of(repoName, UserAndTimestamp.of(author), projectRoles);
        final Change<JsonNode> change =
                Change.ofJsonPatch(METADATA_JSON,
                                   asJsonArray(new TestAbsenceOperation(path),
                                               new AddOperation(path,
                                                                Jackson.valueToTree(newRepositoryMetadata))));
        final String commitSummary =
                "Add a repo '" + newRepositoryMetadata.id() + "' to the project '" + projectName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, change)
                           .handle((revision, cause) -> {
                               if (cause != null) {
                                   if (Exceptions.peel(cause) instanceof ChangeConflictException) {
                                       throw new RepositoryExistsException(repoName);
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
                                   asJsonArray(new TestAbsenceOperation(path),
                                               new AddOperation(path, Jackson.valueToTree(
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
                                                           new RemoveOperation(path).toJsonNode());
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
                                   new RemoveOperation(JsonPointer.compile(
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

        if (Project.REPO_DOGMA.equals(repoName)) {
            throw new UnsupportedOperationException(
                    "Can't update role for internal repository: " + repoName);
        }

        if (Project.REPO_META.equals(repoName)) {
            if (projectRoles.guest() != null) {
                throw new UnsupportedOperationException(
                        "Can't give a role to guest for internal repository: " + repoName);
            }
        }
        final String commitSummary =
                "Update the project roles of the '" + repoName + "' in the project '" + projectName + '\'';
        final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                repoName, (headRevision, repositoryMetadata) -> {
            final Roles newRoles = new Roles(projectRoles, repositoryMetadata.roles().users(),
                                             repositoryMetadata.roles().tokens());
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.writeQuota());
        });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Adds the specified {@link Token} to the specified {@code projectName}.
     */
    public CompletableFuture<Revision> addToken(Author author, String projectName,
                                                Token token, ProjectRole role) {
        return addToken(author, projectName, requireNonNull(token, "token").appId(), role);
    }

    /**
     * Adds a {@link Token} of the specified {@code appId} to the specified {@code projectName}.
     */
    public CompletableFuture<Revision> addToken(Author author, String projectName,
                                                String appId, ProjectRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(appId, "appId");
        requireNonNull(role, "role");

        return getTokens().thenCompose(tokens -> {
            tokens.get(appId); // Will raise an exception if not found.

            final TokenRegistration registration = new TokenRegistration(appId, role,
                                                                         UserAndTimestamp.of(author));
            final JsonPointer path = JsonPointer.compile("/tokens" + encodeSegment(registration.id()));
            final Change<JsonNode> change =
                    Change.ofJsonPatch(METADATA_JSON,
                                       asJsonArray(new TestAbsenceOperation(path),
                                                   new AddOperation(path, Jackson.valueToTree(registration))));
            final String commitSummary = "Add a token '" + registration.id() +
                                         "' to the project '" + projectName + "' with a role '" + role + '\'';
            return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, change);
        });
    }

    /**
     * Removes the specified {@link Token} from the specified {@code projectName}. It also removes
     * every token repository role belonging to the {@link Token} from every {@link RepositoryMetadata}.
     */
    public CompletableFuture<Revision> removeToken(Author author, String projectName, Token token) {
        return removeToken(author, projectName, requireNonNull(token, "token").appId());
    }

    /**
     * Removes the {@link Token} of the specified {@code appId} from the specified {@code projectName}.
     * It also removes every token repository role belonging to {@link Token} from
     * every {@link RepositoryMetadata}.
     */
    public CompletableFuture<Revision> removeToken(Author author, String projectName, String appId) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(appId, "appId");

        return removeToken(projectName, author, appId, false);
    }

    private CompletableFuture<Revision> removeToken(String projectName, Author author, String appId,
                                                    boolean quiet) {
        final String commitSummary = "Remove the token '" + appId + "' from the project '" + projectName + '\'';
        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    final Map<String, TokenRegistration> tokens = projectMetadata.tokens();
                    final Map<String, TokenRegistration> newTokens;
                    if (tokens.get(appId) == null) {
                        if (!quiet) {
                            throw new TokenNotFoundException(
                                    "failed to find the token " + appId + " in project " + projectName);
                        }
                        newTokens = tokens;
                    } else {
                        newTokens = tokens.entrySet()
                                          .stream()
                                          .filter(entry -> !entry.getKey().equals(appId))
                                          .collect(toImmutableMap(Entry::getKey, Entry::getValue));
                    }

                    final ImmutableMap<String, RepositoryMetadata> newRepos =
                            removeTokenFromRepositories(appId, projectMetadata);
                    return new ProjectMetadata(projectMetadata.name(),
                                               newRepos,
                                               projectMetadata.members(),
                                               newTokens,
                                               projectMetadata.creation(),
                                               projectMetadata.removal());
                });
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    private static ImmutableMap<String, RepositoryMetadata> removeTokenFromRepositories(
            String appId, ProjectMetadata projectMetadata) {
        final ImmutableMap.Builder<String, RepositoryMetadata> builder =
                ImmutableMap.builderWithExpectedSize(projectMetadata.repos().size());
        for (Entry<String, RepositoryMetadata> entry : projectMetadata.repos().entrySet()) {
            final RepositoryMetadata repositoryMetadata = entry.getValue();
            final Roles roles = repositoryMetadata.roles();
            if (roles.tokens().get(appId) != null) {
                final Map<String, RepositoryRole> newTokens = removeFromMap(roles.tokens(), appId);
                final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), newTokens);
                builder.put(entry.getKey(), new RepositoryMetadata(repositoryMetadata.name(),
                                                                   newRoles,
                                                                   repositoryMetadata.creation(),
                                                                   repositoryMetadata.removal(),
                                                                   repositoryMetadata.writeQuota()));
            } else {
                builder.put(entry);
            }
        }
        return builder.build();
    }

    /**
     * Updates a {@link ProjectRole} for the {@link Token} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> updateTokenRole(Author author, String projectName,
                                                       Token token, ProjectRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(token, "token");
        requireNonNull(role, "role");
        final TokenRegistration registration = new TokenRegistration(token.appId(), role,
                                                                     UserAndTimestamp.of(author));
        final JsonPointer path = JsonPointer.compile("/tokens" + encodeSegment(registration.id()));
        final Change<JsonNode> change =
                Change.ofJsonPatch(METADATA_JSON,
                                   new ReplaceOperation(path, Jackson.valueToTree(registration))
                                           .toJsonNode());
        final String commitSummary = "Update the role of a token '" + token.appId() +
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
                final Roles newRoles = new Roles(roles.projectRoles(), newUsers, roles.tokens());
                return new RepositoryMetadata(repositoryMetadata.name(),
                                              newRoles,
                                              repositoryMetadata.creation(),
                                              repositoryMetadata.removal(),
                                              repositoryMetadata.writeQuota());
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
            final Roles newRoles = new Roles(roles.projectRoles(), newUsers, roles.tokens());
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.writeQuota());
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
            final Roles newRoles = new Roles(roles.projectRoles(), newUsers, roles.tokens());
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.writeQuota());
        });
        final String commitSummary = "Update repository role of the '" + memberId + "' as '" + role +
                                     "' for '" + projectName + '/' + repoName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Adds the {@link RepositoryRole} for the {@link Token} of the specified {@code appId} to the specified
     * {@code repoName} in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> addTokenRepositoryRole(Author author, String projectName,
                                                              String repoName, String appId,
                                                              RepositoryRole role) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(appId, "appId");
        requireNonNull(role, "role");

        return getProject(projectName).thenCompose(project -> {
            ensureProjectToken(project, appId);
            final String commitSummary = "Add repository role of the token '" + appId + "' as '" + role +
                                         "' to '" + projectName + '/' + repoName + "'\n";
            final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                    repoName, (headRevision, repositoryMetadata) -> {
                final Roles roles = repositoryMetadata.roles();
                if (roles.tokens().get(appId) != null) {
                    throw new ChangeConflictException(
                            "the token " + appId + " is already added to '" +
                            projectName + '/' + repoName + '\'');
                }

                final Map<String, RepositoryRole> newTokens = addToMap(roles.tokens(), appId, role);
                final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), newTokens);
                return new RepositoryMetadata(repositoryMetadata.name(),
                                              newRoles,
                                              repositoryMetadata.creation(),
                                              repositoryMetadata.removal(),
                                              repositoryMetadata.writeQuota());
            });
            return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
        });
    }

    /**
     * Removes the {@link RepositoryRole} for the {@link Token} of the specified {@code appId} of the specified
     * {@code repoName} in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> removeTokenRepositoryRole(Author author, String projectName,
                                                                 String repoName, String appId) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(appId, "appId");

        final RepositoryMetadataTransformer transformer = new RepositoryMetadataTransformer(
                repoName, (headRevision, repositoryMetadata) -> {
            final Roles roles = repositoryMetadata.roles();
            if (roles.tokens().get(appId) == null) {
                throw new ChangeConflictException(
                        "the token " + appId + " doesn't exist at '" +
                        projectName + '/' + repoName + '\'');
            }

            final Map<String, RepositoryRole> newTokens = removeFromMap(roles.tokens(), appId);
            final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), newTokens);
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.writeQuota());
        });
        final String commitSummary = "Remove repository role of the token '" + appId +
                                     "' from '" + projectName + '/' + repoName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Updates the {@link RepositoryRole} for the {@link Token} of the specified {@code appId} of the specified
     * {@code repoName} in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> updateTokenRepositoryRole(Author author, String projectName,
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
            final RepositoryRole oldRepositoryRole = roles.tokens().get(appId);
            if (oldRepositoryRole == null) {
                throw new TokenNotFoundException(
                        "the token " + appId + " doesn't exist at '" +
                        projectName + '/' + repoName + '\'');
            }

            if (oldRepositoryRole == role) {
                throw new RedundantChangeException(
                        headRevision,
                        "the permission of " + appId + " in '" + projectName + '/' + repoName +
                        "' isn't changed.");
            }

            final Map<String, RepositoryRole> newTokens = updateMap(roles.tokens(), appId, role);
            final Roles newRoles = new Roles(roles.projectRoles(), roles.users(), newTokens);
            return new RepositoryMetadata(repositoryMetadata.name(),
                                          newRoles,
                                          repositoryMetadata.creation(),
                                          repositoryMetadata.removal(),
                                          repositoryMetadata.writeQuota());
        });
        final String commitSummary = "Update repository role of the token '" + appId +
                                     "' for '" + projectName + '/' + repoName + '\'';
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Updates the {@linkplain QuotaConfig write quota} for the specified {@code repoName}
     * in the specified {@code projectName}.
     */
    public CompletableFuture<Revision> updateWriteQuota(
            Author author, String projectName, String repoName, QuotaConfig writeQuota) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(writeQuota, "writeQuota");

        final JsonPointer path = JsonPointer.compile("/repos" + encodeSegment(repoName) + "/writeQuota");
        final Change<JsonNode> change =
                Change.ofJsonPatch(METADATA_JSON,
                                   new AddOperation(path, Jackson.valueToTree(writeQuota)).toJsonNode());
        final String commitSummary = "Update a write quota for the repository '" + repoName + '\'';
        executor.setWriteQuota(projectName, repoName, writeQuota);
        return metadataRepo.push(projectName, Project.REPO_DOGMA, author, commitSummary, change);
    }

    /**
     * Finds {@link RepositoryRole} of the specified {@link User} or {@link UserWithToken}
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
        if (user instanceof UserWithToken) {
            return findRepositoryRole(projectName, repoName, ((UserWithToken) user).token());
        }
        return findRepositoryRole0(projectName, repoName, user);
    }

    /**
     * Finds {@link RepositoryRole} of the specified {@link Token} from the specified
     * {@code repoName} in the specified {@code projectName}. If the {@code appId} is not found,
     * it will return {@code null}.
     */
    public CompletableFuture<RepositoryRole> findRepositoryRole(String projectName, String repoName,
                                                                Token token) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(token, "token");

        return getProject(projectName).thenApply(metadata -> {
            final RepositoryMetadata repositoryMetadata = metadata.repo(repoName);
            final Roles roles = repositoryMetadata.roles();
            final String appId = token.appId();
            final RepositoryRole tokenRepositoryRole = roles.tokens().get(appId);

            final TokenRegistration projectTokenRegistration = metadata.tokens().get(appId);
            final ProjectRole projectRole;
            if (projectTokenRegistration != null) {
                projectRole = projectTokenRegistration.role();
            } else {
                // System admin tokens were checked before this method.
                assert !token.isSystemAdmin();
                if (token.allowGuestAccess()) {
                    projectRole = ProjectRole.GUEST;
                } else {
                    // The token is not allowed with the GUEST permission.
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
            if (user instanceof UserWithToken) {
                final TokenRegistration registration = project.tokens().getOrDefault(
                        ((UserWithToken) user).token().id(), null);
                return registration != null ? registration.role() : ProjectRole.GUEST;
            } else {
                final Member member = project.memberOrDefault(user.id(), null);
                return member != null ? member.role() : ProjectRole.GUEST;
            }
        });
    }

    /**
     * Returns a {@link Tokens}.
     */
    public CompletableFuture<Tokens> getTokens() {
        return tokenRepo.fetch(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, TOKEN_JSON)
                        .thenApply(HolderWithRevision::object);
    }

    /**
     * Creates a new user-level {@link Token} with the specified {@code appId}. A secret for the {@code appId}
     * will be automatically generated.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId) {
        return createToken(author, appId, false);
    }

    /**
     * Creates a new {@link Token} with the specified {@code appId}, {@code isSystemAdmin} and an auto-generated
     * secret.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId, boolean isSystemAdmin) {
        return createToken(author, appId, SECRET_PREFIX + UUID.randomUUID(), isSystemAdmin);
    }

    /**
     * Creates a new user-level {@link Token} with the specified {@code appId} and {@code secret}.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId, String secret) {
        return createToken(author, appId, secret, false);
    }

    /**
     * Creates a new {@link Token} with the specified {@code appId}, {@code secret} and {@code isSystemAdmin}.
     */
    public CompletableFuture<Revision> createToken(Author author, String appId, String secret,
                                                   boolean isSystemAdmin) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        requireNonNull(secret, "secret");

        checkArgument(secret.startsWith(SECRET_PREFIX), "secret must start with: " + SECRET_PREFIX);

        // Does not allow guest access for normal tokens.
        final boolean allowGuestAccess = isSystemAdmin;
        final Token newToken = new Token(appId, secret, isSystemAdmin, allowGuestAccess,
                                         UserAndTimestamp.of(author));
        final JsonPointer appIdPath = JsonPointer.compile("/appIds" + encodeSegment(newToken.id()));
        final String newTokenSecret = newToken.secret();
        assert newTokenSecret != null;
        final JsonPointer secretPath = JsonPointer.compile("/secrets" + encodeSegment(newTokenSecret));
        final Change<JsonNode> change =
                Change.ofJsonPatch(TOKEN_JSON,
                                   asJsonArray(new TestAbsenceOperation(appIdPath),
                                               new TestAbsenceOperation(secretPath),
                                               new AddOperation(appIdPath, Jackson.valueToTree(newToken)),
                                               new AddOperation(secretPath,
                                                                Jackson.valueToTree(newToken.id()))));
        return tokenRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                              "Add a token: " + newToken.id(), change);
    }

    /**
     * Removes the {@link Token} of the specified {@code appId} completely from the system.
     */
    public CompletableFuture<Revision> destroyToken(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Destroy the token: " + appId;
        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);

        final TokensTransformer transformer = new TokensTransformer((headRevision, tokens) -> {
            final Token token = tokens.get(appId); // Raise an exception if not found.
            if (token.deletion() != null) {
                throw new ChangeConflictException("The token is already destroyed: " + appId);
            }

            final String secret = token.secret();
            assert secret != null;
            final Token newToken = new Token(token.appId(), secret, token.isSystemAdmin(),
                                             token.isSystemAdmin(), token.allowGuestAccess(),
                                             token.creation(), token.deactivation(), userAndTimestamp);
            return new Tokens(updateMap(tokens.appIds(), appId, newToken), tokens.secrets());
        });
        return tokenRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Purges the {@link Token} of the specified {@code appId} that was removed before.
     *
     * <p>Note that this is a blocking method that should not be invoked in an event loop.
     */
    public Revision purgeToken(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final Collection<Project> projects = listProjectsWithoutInternal(projectManager.list(),
                                                                         User.SYSTEM_ADMIN).values();
        // Remove the token from projects that only have the token.
        for (Project project : projects) {
            final ProjectMetadata projectMetadata = fetchMetadata(project.name()).join().object();
            final boolean containsTargetTokenInTheProject =
                    projectMetadata.tokens().values()
                                   .stream()
                                   .anyMatch(token -> token.appId().equals(appId));

            if (containsTargetTokenInTheProject) {
                removeToken(project.name(), author, appId, true).join();
            }
        }

        final String commitSummary = "Remove the token: " + appId;

        final TokensTransformer transformer = new TokensTransformer((headRevision, tokens) -> {
            final Token token = tokens.get(appId);
            final Map<String, Token> newAppIds = removeFromMap(tokens.appIds(), appId);
            final String secret = token.secret();
            assert secret != null;
            final Map<String, String> newSecrets = removeFromMap(tokens.secrets(), secret);
            return new Tokens(newAppIds, newSecrets);
        });
        return tokenRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author, commitSummary, transformer)
                        .join();
    }

    /**
     * Activates the {@link Token} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> activateToken(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Enable the token: " + appId;

        final TokensTransformer transformer = new TokensTransformer((headRevision, tokens) -> {
            final Token token = tokens.get(appId); // Raise an exception if not found.
            if (token.deactivation() == null) {
                throw new RedundantChangeException(headRevision, "The token is already activated: " + appId);
            }
            final String secret = token.secret();
            assert secret != null;
            final Map<String, String> newSecrets =
                    addToMap(tokens.secrets(), secret, appId); // Note that the key is secret not appId.
            final Token newToken = new Token(token.appId(), secret, token.isSystemAdmin(),
                                             token.allowGuestAccess(), token.creation());
            return new Tokens(updateMap(tokens.appIds(), appId, newToken), newSecrets);
        });
        return tokenRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Deactivates the {@link Token} of the specified {@code appId}.
     */
    public CompletableFuture<Revision> deactivateToken(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Deactivate the token: " + appId;
        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);

        final TokensTransformer transformer = new TokensTransformer((headRevision, tokens) -> {
            final Token token = tokens.get(appId);
            if (token.deactivation() != null) {
                throw new RedundantChangeException(headRevision, "The token is already deactivated: " + appId);
            }
            final String secret = token.secret();
            assert secret != null;
            final Token newToken = new Token(token.appId(), secret, token.isSystemAdmin(),
                                             token.isSystemAdmin(), token.allowGuestAccess(), token.creation(),
                                             userAndTimestamp, null);
            final Map<String, Token> newAppIds = updateMap(tokens.appIds(), appId, newToken);
            final Map<String, String> newSecrets =
                    removeFromMap(tokens.secrets(), secret); // Note that the key is secret not appId.
            return new Tokens(newAppIds, newSecrets);
        });
        return tokenRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Update the {@link Token} of the specified {@code appId} to user or admin.
     */
    public CompletableFuture<Revision> updateTokenLevel(Author author, String appId, boolean toBeSystemAdmin) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        final String commitSummary =
                "Update the token level: " + appId + " to " + (toBeSystemAdmin ? "admin" : "user");
        final TokensTransformer transformer = new TokensTransformer((headRevision, tokens) -> {
            final Token token = tokens.get(appId); // Raise an exception if not found.
            if (toBeSystemAdmin == token.isSystemAdmin()) {
                throw new RedundantChangeException(
                        headRevision,
                        "The token is already " + (toBeSystemAdmin ? "admin" : "user"));
            }

            final Token newToken = token.withSystemAdmin(toBeSystemAdmin);
            return new Tokens(updateMap(tokens.appIds(), appId, newToken), tokens.secrets());
        });
        return tokenRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author, commitSummary, transformer);
    }

    /**
     * Returns a {@link Token} which has the specified {@code appId}.
     */
    public CompletableFuture<Token> findTokenByAppId(String appId) {
        requireNonNull(appId, "appId");
        return tokenRepo.fetch(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, TOKEN_JSON)
                        .thenApply(tokens -> tokens.object().get(appId));
    }

    /**
     * Returns a {@link Token} which has the specified {@code secret}.
     */
    public CompletableFuture<Token> findTokenBySecret(String secret) {
        requireNonNull(secret, "secret");
        validateSecret(secret);
        return tokenRepo.fetch(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, TOKEN_JSON)
                        .thenApply(tokens -> tokens.object().findBySecret(secret));
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
     * Ensures that the specified {@code appId} is a token of the specified {@code project}.
     */
    private static void ensureProjectToken(ProjectMetadata project, String appId) {
        requireNonNull(project, "project");
        requireNonNull(appId, "appId");

        if (!project.tokens().containsKey(appId)) {
            throw new TokenNotFoundException(
                    appId + " is not a token of the project '" + project.name() + '\'');
        }
    }

    private static <T> ImmutableMap<String, T> addToMap(Map<String, T> map, String key, T value) {
        return ImmutableMap.<String, T>builderWithExpectedSize(map.size() + 1)
                           .putAll(map)
                           .put(key, value)
                           .build();
    }

    private static <T> Map<String, T> updateMap(Map<String, T> map, String key, T value) {
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

    private static <T> ImmutableMap<String, T> removeFromMap(Map<String, T> map, String id) {
        return map.entrySet().stream()
                  .filter(e -> !e.getKey().equals(id))
                  .collect(toImmutableMap(Entry::getKey, Entry::getValue));
    }
}
