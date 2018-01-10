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

package com.linecorp.centraldogma.server.internal.admin.service;

import static com.linecorp.centraldogma.server.internal.admin.service.RepositoryUtil.push;
import static java.util.Objects.requireNonNull;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Predicate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.admin.model.MemberInfo;
import com.linecorp.centraldogma.server.internal.admin.model.ProjectInfo;
import com.linecorp.centraldogma.server.internal.admin.model.ProjectRole;
import com.linecorp.centraldogma.server.internal.admin.model.RepoInfo;
import com.linecorp.centraldogma.server.internal.admin.model.TokenInfo;
import com.linecorp.centraldogma.server.internal.admin.model.UserAndTimestamp;
import com.linecorp.centraldogma.server.internal.api.AbstractService;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.command.ProjectInitializer;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectExistsException;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.project.SafeProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.ChangeConflictException;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * A service class for metadata management. This service stores metadata into the {@value METADATA_PROJECT}
 * project which is internally used, so it uses the {@link ProjectManager} given by the caller, not wrapped
 * by {@link SafeProjectManager}.
 */
public class MetadataService extends AbstractService {

    public static final String METADATA_PROJECT = ProjectInitializer.INTERNAL_PROJECT_NAME;
    public static final String REPO = Project.REPO_MAIN;
    public static final String METADATA_JSON = "/metadata.json";

    private static final TypeReference<List<ProjectInfo>>
            PROJECT_LIST_TYPE_REF = new TypeReference<List<ProjectInfo>>() {};
    private static final TypeReference<List<TokenInfo>>
            TOKEN_LIST_TYPE_REF = new TypeReference<List<TokenInfo>>() {};

    private static final String SECRET_PREFIX = "appToken-";

    public MetadataService(ProjectManager projectManager, CommandExecutor executor) {
        super(projectManager, executor);
        initialize();
    }

    /**
     * Initializes a repository by creating a {@value METADATA_JSON} file in {@value REPO} repository.
     * The file would be created only if it does not exist.
     */
    public void initialize() {
        synchronized (MetadataService.class) {
            final Repository repo = projectManager().get(METADATA_PROJECT).repos()
                                                    .get(REPO);
            final Revision normalizedRevision = repo.normalizeNow(Revision.HEAD);
            final Map<String, Entry<?>> entries = repo.find(normalizedRevision, "/*").join();
            if (!entries.containsKey(METADATA_JSON)) {
                //TODO(hyangtack) Need to make metadata of the existing projects in the next PRs.
                execute(Command.push(Author.SYSTEM,
                                     METADATA_PROJECT, REPO, normalizedRevision,
                                     "Create " + METADATA_JSON, "",
                                     Markup.PLAINTEXT,
                                     Change.ofJsonUpsert(METADATA_JSON, "[]"))).join();
            }
        }
    }

    /**
     * Returns all {@link ProjectInfo} as a {@link List}.
     */
    public CompletionStage<List<ProjectInfo>> getAllProjects() {
        return query("$.*").thenApply(MetadataService::toProjectInfoList);
    }

    /**
     * Returns valid {@link ProjectInfo} as a {@link List}.
     */
    public CompletionStage<List<ProjectInfo>> getValidProjects() {
        return query("$[?(@.removed != true)]").thenApply(MetadataService::toProjectInfoList);
    }

    /**
     * Returns a {@link ProjectInfo} whose name equals to the specified {@code projectName}.
     */
    public CompletionStage<ProjectInfo> getProject(String projectName) {
        requireNonNull(projectName, "projectName");
        return query("$[?(@.name == \"" + projectName + "\" && @.removed != true)]")
                .thenApply(result -> toSingleProjectInfo(projectName, result));
    }

    /**
     * Returns {@link ProjectInfo}s belonging to the specified {@link User}.
     */
    public CompletionStage<List<ProjectInfo>> findProjects(User user) {
        requireNonNull(user, "user");
        final String q = "$[?(@.members[?(@.login == \"" + user.name() +
                         "\")] empty false && @.removed != true)]";
        return query(q).thenApply(MetadataService::toProjectInfoList);
    }

    /**
     * Returns {@link ProjectRole}s of the specified {@link User}.
     */
    public CompletionStage<Map<String, ProjectRole>> findRoles(User user) {
        return findProjects(user).thenApply(list -> {
            final ImmutableMap.Builder<String, ProjectRole> builder = new ImmutableMap.Builder<>();
            list.forEach(p -> p.members().stream()
                               .filter(m -> user.name().equals(m.login()))
                               .findFirst()
                               .ifPresent(m -> builder.put(p.name(), m.role())));
            return builder.build();
        });
    }

    /**
     * Returns {@link ProjectRole}s of the specified secret of a {@link TokenInfo}.
     */
    public CompletionStage<Map<String, ProjectRole>> findRole(String secret) {
        requireNonNull(secret, "secret");
        final String q = "$[?(@.tokens[?(@.secret == \"" + secret +
                         "\")] empty false && @.removed != true)]";
        return query(q)
                .thenApply(MetadataService::toProjectInfoList)
                .thenApply(list -> {
                    final ImmutableMap.Builder<String, ProjectRole> builder = new ImmutableMap.Builder<>();
                    list.forEach(p -> p.tokens().stream()
                                       .filter(t -> t.secret().equals(secret))
                                       .findFirst()
                                       .ifPresent(m -> builder.put(p.name(), m.role())));
                    return builder.build();
                });
    }

    /**
     * Returns {@link ProjectRole} of the specified {@link User} in the specified {@code projectName}.
     */
    public CompletionStage<ProjectRole> findRole(String projectName, User user) {
        requireNonNull(projectName, "projectName");
        requireNonNull(user, "user");
        final String q = "$[?(@.members[?(@.login == \"" + user.name() +
                         "\")] empty false && @.name == \"" + projectName +
                         "\" && @.removed != true)]";
        return query(q).thenApply(MetadataService::toProjectInfoList)
                       .thenApply(list -> {
                           if (list == null || list.isEmpty()) {
                               return ProjectRole.NONE;
                           }
                           return list.get(0)
                                      .members().stream()
                                      .filter(e -> user.name().equals(e.login()))
                                      .findFirst()
                                      .map(MemberInfo::role)
                                      .orElse(ProjectRole.NONE);
                       });
    }

    /**
     * Adds a {@link ProjectInfo} object to metadata and returns it. We do not check the pattern of
     * the {@code name} here. It should be done by the caller before calling this method.
     */
    public CompletionStage<ProjectInfo> createProject(String projectName, Author author) {
        return createProject(projectName, author, ImmutableSet.of(), ImmutableSet.of());
    }

    /**
     * Adds a {@link ProjectInfo} object to metadata and returns it. We do not check the pattern of
     * the {@code name} here. It should be done by the caller before calling this method.
     */
    public CompletionStage<ProjectInfo> createProject(String projectName, Author author,
                                                      Set<String> owners, Set<String> members) {
        requireNonNull(projectName, "projectName");
        requireNonNull(author, "author");
        requireNonNull(owners, "owners");
        requireNonNull(members, "members");
        return getAllProjects().thenCompose(list -> {
            final boolean isExist = list.stream().anyMatch(e -> projectName.equals(e.name()));
            if (isExist) {
                throw new ProjectExistsException(projectName);
            }

            final List<ProjectInfo> oldList = ImmutableList.copyOf(list);
            final UserAndTimestamp userAndTimestamp = new UserAndTimestamp(author.name());

            final ImmutableList.Builder<MemberInfo> memberInfos = new Builder<>();
            owners.forEach(o -> memberInfos.add(new MemberInfo(o, ProjectRole.OWNER, userAndTimestamp)));
            members.forEach(m -> memberInfos.add(new MemberInfo(m, ProjectRole.MEMBER, userAndTimestamp)));

            final ProjectInfo newProject = new ProjectInfo(projectName, userAndTimestamp, memberInfos.build());
            list.add(newProject);
            list.sort(Comparator.comparing(ProjectInfo::name));
            final Change<JsonNode> change = Change.ofJsonPatch(METADATA_JSON,
                                                               Jackson.valueToTree(oldList),
                                                               Jackson.valueToTree(list));
            return pushChanges(change, author, "Create a project " + projectName);
        }).thenCompose(unused -> getProject(projectName));
    }

    /**
     * Removes a {@link ProjectInfo} whose name equals to the specified {@code name}.
     */
    public CompletionStage<ProjectInfo> removeProject(String projectName, Author author) {
        requireNonNull(projectName, "projectName");
        requireNonNull(author, "author");
        final String commitSummary = "Remove a project " + projectName;
        return getAllProjects().thenCompose(
                list -> updateProjectInfo(author, commitSummary, list, projectName, p ->
                        new ProjectInfo(p.name(),
                                        p.repos(), p.members(), p.tokens(),
                                        p.creation(),
                                        new UserAndTimestamp(author.name()))));
    }

    /**
     * Unremoves a {@link ProjectInfo} whose name equals to the specified {@code name}.
     */
    public CompletionStage<ProjectInfo> unremoveProject(String projectName, Author author) {
        requireNonNull(projectName, "projectName");
        requireNonNull(author, "author");
        final String commitSummary = "Unremove the project " + projectName;
        return getAllProjects().thenCompose(
                list -> updateProjectInfo(author, commitSummary, list, projectName, p ->
                        new ProjectInfo(p.name(),
                                        p.repos(), p.members(), p.tokens(),
                                        p.creation(), null), true));
    }

    /**
     * Adds a {@link User} to the {@link ProjectInfo} which name is {@code name}.
     */
    public CompletionStage<ProjectInfo> addMember(String projectName, Author author,
                                                  User member, ProjectRole projectRole) {
        requireNonNull(projectName, "projectName");
        requireNonNull(author, "author");
        requireNonNull(member, "member");
        requireNonNull(projectRole, "projectRole");
        final String commitSummary = "Add a member " + member.name() + " to a project " + projectName;
        return getAllProjects().thenCompose(
                list -> updateProjectInfo(author, commitSummary, list, projectName, p ->
                        p.duplicateWithMembers(ImmutableList.<MemberInfo>builder()
                                                       .addAll(p.members())
                                                       .add(new MemberInfo(member.name(), projectRole,
                                                                           new UserAndTimestamp(author.name())))
                                                       .build())));
    }

    /**
     * Removes a {@link User} from the {@link ProjectInfo} which name is {@code name}.
     */
    public CompletionStage<ProjectInfo> removeMember(String projectName, Author author, User member) {
        requireNonNull(projectName, "projectName");
        requireNonNull(author, "author");
        requireNonNull(member, "member");
        final String commitSummary = "Remove a member " + member.name() + " from a project " + projectName;
        return getAllProjects().thenCompose(
                list -> updateProjectInfo(author, commitSummary, list, projectName, p ->
                        p.duplicateWithMembers(collect(p.members(), e -> !e.login().equals(member.name()))
                                                       .build())));
    }

    /**
     * Changes a {@link ProjectRole} of a {@link User}.
     */
    public CompletionStage<ProjectInfo> changeMemberRole(String projectName, Author author,
                                                         User member, ProjectRole projectRole) {
        requireNonNull(projectName, "projectName");
        requireNonNull(author, "author");
        requireNonNull(member, "member");
        requireNonNull(projectRole, "projectRole");
        final String commitSummary = "Change a projectRole of " + member.name() + " to " + projectRole +
                                     " from a project " + projectName;
        return getAllProjects().thenCompose(
                list -> updateProjectInfo(author, commitSummary, list, projectName, p ->
                        p.duplicateWithMembers(collect(p.members(), e -> !e.login().equals(member.name()))
                                                       .add(new MemberInfo(member.name(), projectRole,
                                                                           new UserAndTimestamp(author.name())))
                                                       .build())));
    }

    /**
     * Adds a {@link RepoInfo} named as {@code repoName} to a {@link ProjectInfo}.
     */
    public CompletionStage<ProjectInfo> addRepo(String projectName, Author author, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(author, "author");
        requireNonNull(repoName, "repoName");
        final String commitSummary = "Add a repo " + repoName + " to a project " + projectName;
        return getAllProjects().thenCompose(
                list -> updateProjectInfo(author, commitSummary, list, projectName, p ->
                        p.duplicateWithRepos(ImmutableList.<RepoInfo>builder()
                                                     .addAll(p.repos())
                                                     .add(new RepoInfo(repoName,
                                                                       new UserAndTimestamp(author.name())))
                                                     .build())));
    }

    /**
     * Removes a {@link RepoInfo} named as {@code repoName} from a {@link ProjectInfo}.
     */
    public CompletionStage<ProjectInfo> removeRepo(String projectName, Author author, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(author, "author");
        requireNonNull(repoName, "repoName");
        final String commitSummary = "Remove a repo " + repoName + " from a project " + projectName;
        return getAllProjects().thenCompose(
                list -> updateProjectInfo(author, commitSummary, list, projectName, p ->
                        p.duplicateWithRepos(collect(p.repos(), e -> !e.name().equals(repoName))
                                                     .build())));
    }

    /**
     * Returns a {@link TokenInfo} belonging to the specified {@code appId}.
     */
    public CompletionStage<TokenInfo> findToken(String projectName, String appId) {
        requireNonNull(projectName, "projectName");
        requireNonNull(appId, "appId");
        final String q = "$[?(@.name == \"" + projectName +
                         "\" && @.removed != true)].tokens[?(@.appId == \"" + appId + "\")]";
        return query(q).thenApply(MetadataService::toTokenInfoList)
                       .thenApply(list -> list != null && !list.isEmpty() ? list.get(0) : null);
    }

    /**
     * Adds a new {@link TokenInfo} to a {@link ProjectInfo} with an auto-generated secret.
     */
    public CompletionStage<ProjectInfo> addToken(String projectName, Author author,
                                                 String appId, ProjectRole projectRole) {
        return addToken(projectName, author, appId, SECRET_PREFIX + UUID.randomUUID(), projectRole);
    }

    /**
     * Adds a new {@link TokenInfo} to a {@link ProjectInfo}.
     */
    public CompletionStage<ProjectInfo> addToken(String projectName, Author author,
                                                 String appId, String secret, ProjectRole projectRole) {
        requireNonNull(projectName, "projectName");
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        requireNonNull(secret, "secret");
        requireNonNull(projectRole, "projectRole");
        final String commitSummary = "Add a token " + appId + " to a project " + projectName;
        return getAllProjects().thenCompose(
                list -> updateProjectInfo(author, commitSummary, list, projectName, p ->
                        p.duplicateWithTokens(ImmutableList.<TokenInfo>builder()
                                                      .addAll(p.tokens())
                                                      .add(new TokenInfo(appId, secret, projectRole,
                                                                         new UserAndTimestamp(author.name())))
                                                      .build())));
    }

    /**
     * Removes the {@link TokenInfo} belonging to {@code appId} from {@link ProjectInfo} of {@code projectName}.
     */
    public CompletionStage<ProjectInfo> removeToken(String projectName, Author author, String appId) {
        requireNonNull(projectName, "projectName");
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        final String commitSummary = "Remove a token " + appId + " from a project " + projectName;
        return getAllProjects().thenCompose(
                list -> updateProjectInfo(author, commitSummary, list, projectName, p ->
                        p.duplicateWithTokens(collect(p.tokens(), e -> !e.appId().equals(appId))
                                                      .build())));
    }

    /**
     * Changes a {@link ProjectRole} of a {@link TokenInfo}.
     */
    public CompletionStage<ProjectInfo> changeTokenRole(String projectName, Author author,
                                                        String appId, ProjectRole projectRole) {
        requireNonNull(projectName, "projectName");
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        requireNonNull(projectRole, "projectRole");
        final String commitSummary = "Change the projectRole of '" + appId + "' to " + projectRole +
                                     " for the project " + projectName;
        return getAllProjects().thenCompose(
                list -> updateProjectInfo(author, commitSummary, list, projectName, p -> {
                    final ImmutableList.Builder<TokenInfo> newTokens = new ImmutableList.Builder<>();
                    for (final TokenInfo tokenInfo : p.tokens()) {
                        if (tokenInfo.appId().equals(appId)) {
                            if (tokenInfo.role() == projectRole) {
                                throw new ChangeConflictException("Same project role: " + projectRole);
                            }
                            // We need to keep the old secret. So we handle this in a different way from
                            // the case of changing a role of a member.
                            newTokens.add(new TokenInfo(tokenInfo.appId(), tokenInfo.secret(),
                                                        projectRole, tokenInfo.creation()));
                        } else {
                            newTokens.add(tokenInfo);
                        }
                    }
                    return p.duplicateWithTokens(newTokens.build());
                }));
    }

    @VisibleForTesting
    CompletionStage<String> getRawMetadata() {
        return projectManager()
                .get(METADATA_PROJECT).repos().get(REPO)
                .get(Revision.HEAD, METADATA_JSON)
                .thenApply(entry -> {
                    try {
                        return Jackson.writeValueAsPrettyString(entry.content());
                    } catch (JsonProcessingException ignore) {
                        return "";
                    }
                });
    }

    private CompletionStage<ProjectInfo> updateProjectInfo(Author author, String commitSummary,
                                                           List<ProjectInfo> list, String projectName,
                                                           Function<ProjectInfo, ProjectInfo> updater) {
        return updateProjectInfo(author, commitSummary, list, projectName, updater, false);
    }

    private CompletionStage<ProjectInfo> updateProjectInfo(Author author, String commitSummary,
                                                           List<ProjectInfo> list, String projectName,
                                                           Function<ProjectInfo, ProjectInfo> updater,
                                                           boolean forceUpdate) {
        final ImmutableList.Builder<ProjectInfo> newList = ImmutableList.builder();
        ProjectInfo target = null;
        for (ProjectInfo p : list) {
            if ((forceUpdate || !p.isRemoved()) && p.name().equals(projectName)) {
                p = updater.apply(p);
                if (target != null) {
                    throw new IllegalStateException("Project '" + projectName + "' is not unique.");
                }
                target = p;
            }
            newList.add(p);
        }
        if (target == null) {
            throw new ProjectNotFoundException(projectName);
        }

        final ProjectInfo ret = target;
        final Change<JsonNode> change = Change.ofJsonPatch(METADATA_JSON,
                                                           Jackson.valueToTree(list),
                                                           Jackson.valueToTree(newList.build()));
        return pushChanges(change, author, commitSummary)
                .thenApply(unused -> ret);
    }

    private CompletableFuture<QueryResult<JsonNode>> query(String query) {
        return projectManager()
                .get(METADATA_PROJECT).repos().get(REPO)
                .get(Revision.HEAD, Query.ofJsonPath(METADATA_JSON, query));
    }

    private CompletionStage<?> pushChanges(Change<JsonNode> change,
                                           Author author, String commitSummary) {
        return push(this, METADATA_PROJECT, REPO, Revision.HEAD,
                    author, commitSummary, "", Markup.PLAINTEXT, change);
    }

    private static <T> ImmutableList.Builder<T> collect(List<T> list,
                                                        Predicate<? super T> predicate) {
        final ImmutableList.Builder<T> builder = new Builder<>();
        list.stream().filter(predicate).forEach(builder::add);
        return builder;
    }

    private static ProjectInfo toSingleProjectInfo(String projectName,
                                                   QueryResult<JsonNode> result) {
        final List<ProjectInfo> list = toProjectInfoList(result);
        if (list.isEmpty()) {
            throw new ProjectNotFoundException(projectName);
        }
        if (list.size() > 1) {
            throw new IllegalStateException("Project '" + projectName + "' is not unique.");
        }
        return list.get(0);
    }

    private static List<ProjectInfo> toProjectInfoList(QueryResult<JsonNode> result) {
        if (!isValid(result)) {
            return null;
        }
        return Jackson.convertValue(result.content(), PROJECT_LIST_TYPE_REF);
    }

    private static List<TokenInfo> toTokenInfoList(QueryResult<JsonNode> result) {
        if (!isValid(result)) {
            return null;
        }
        return Jackson.convertValue(result.content(), TOKEN_LIST_TYPE_REF);
    }

    private static boolean isValid(QueryResult<JsonNode> result) {
        return !result.isRemoved() && result.type() == EntryType.JSON;
    }
}
