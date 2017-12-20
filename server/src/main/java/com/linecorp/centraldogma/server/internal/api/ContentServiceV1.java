/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.api;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.internal.Util.isValidJsonFilePath;
import static com.linecorp.centraldogma.internal.Util.validateFilePath;
import static com.linecorp.centraldogma.internal.Util.validateJsonFilePath;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.newHttpResponseException;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.returnOrThrow;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.CommitMessageDto;
import com.linecorp.centraldogma.internal.api.v1.EntryDto;
import com.linecorp.centraldogma.internal.api.v1.WatchResultDto;
import com.linecorp.centraldogma.server.internal.admin.decorator.ProjectMembersOnly;
import com.linecorp.centraldogma.server.internal.api.WatchRequestConverter.WatchRequest;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.FindOption;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * Annotated service object for managing and watching contents.
 */
@ExceptionHandler(HttpApiExceptionHandler.class)
public class ContentServiceV1 extends AbstractService {

    private final WatchService watchService;

    public ContentServiceV1(ProjectManager projectManager, CommandExecutor executor,
                            WatchService watchService) {
        super(projectManager, executor);
        this.watchService = requireNonNull(watchService, "watchService");
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/tree{path}?revision={revision}
     *
     * <p>Returns the list of files in the path.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repos/(?<repoName>[^/]+)/tree(?<path>(|/.*))$")
    @Decorator(ProjectMembersOnly.class)
    public CompletionStage<List<EntryDto<?>>> listFiles(@Param("path") String path,
                                                        @Param("revision") @Default("-1") String revision,
                                                        @RequestObject Repository repository) {
        final String path0 = rootDirIfEmpty(path);
        return listFiles(repository, path0, new Revision(revision),
                         ImmutableMap.of(FindOption.FETCH_CONTENT, false));
    }

    private static CompletionStage<List<EntryDto<?>>> listFiles(Repository repository, String path,
                                                                Revision revision,
                                                                Map<FindOption<?>, ?> options) {
        final String pathPattern = appendWildCardIfDirectory(path);
        return repository.find(revision, pathPattern, options)
                         .thenApply(entries -> entries.values().stream()
                                                      .filter(entry -> entry.type() != EntryType.DIRECTORY)
                                                      .map(DtoConverter::convert).collect(toImmutableList()));
    }

    private static String appendWildCardIfDirectory(String path) {
        return path.endsWith("/") ? path + '*' : path;
    }

    /**
     * Returns "/" if the specified path is null or empty.
     */
    private static String rootDirIfEmpty(String path) {
        return isNullOrEmpty(path) ? "/" : path;
    }

    /**
     * POST /projects/{projectName}/repos/{repoName}/contents?revision={revision}
     *
     * <p>Adds or edits a file.
     */
    @Post("/projects/{projectName}/repos/{repoName}/contents")
    @Decorator(ProjectMembersOnly.class)
    public CompletionStage<EntryDto<?>> addOrEditFile(@Param("revision") @Default("-1") String revision,
                                                      @RequestObject Repository repository,
                                                      @RequestObject JsonNode node,
                                                      @RequestObject Author author) {
        final Entry<CommitMessageDto, Change<?>> commitMessageAndChange = commitMessageAndChange(node);
        final Change<?> change = commitMessageAndChange.getValue();
        final CompletableFuture<Revision> revisionFuture = repository.normalize(new Revision(revision));

        return revisionFuture.thenCompose(normalizedRevision -> {
            final CompletableFuture<Map<String, Change<?>>> changesFuture =
                    repository.previewDiff(normalizedRevision, change);

            final long commitTimeMillis = System.currentTimeMillis();
            final CommitMessageDto commitMessage = commitMessageAndChange.getKey();
            final CompletableFuture<Revision> resultRevisionFuture = changesFuture.thenCompose(
                    changes -> push(commitTimeMillis, author, repository, normalizedRevision,
                                    commitMessage, changes.values()));

            final EntryType type = entryTypeFromChange(change);
            return resultRevisionFuture.thenApply(result -> DtoConverter.convert(
                    result, repository.parent().name(), repository.name(), change.path(), type,
                    commitTimeMillis));
        });
    }

    private static Entry<CommitMessageDto, Change<?>> commitMessageAndChange(JsonNode node) {
        checkArgument(node.get("path") != null && node.get("commitMessage") != null &&
                      node.get("content") != null, "need a path, a content and a commit message to commit");

        final JsonNode contentNode = node.get("content");
        final String path = node.get("path").textValue();
        final Change<?> change = getChange(contentNode, path);
        final CommitMessageDto commitMessage = convertCommitMessage(node.get("commitMessage"));
        return Maps.immutableEntry(commitMessage, change);
    }

    private static Change<?> getChange(JsonNode contentNode, String path) {
        if (contentNode.getNodeType() == JsonNodeType.STRING) {
            validateFilePath(path, "path");
            return Change.ofTextUpsert(path, contentNode.textValue());
        }

        validateJsonFilePath(path, "path");
        return Change.ofJsonUpsert(path, contentNode);
    }

    private static CommitMessageDto convertCommitMessage(JsonNode jsonNode) {
        final CommitMessageDto commitMessage = Jackson.convertValue(jsonNode, CommitMessageDto.class);
        checkArgument(!isNullOrEmpty(commitMessage.summary()), "summary should be non-null");
        return commitMessage;
    }

    private static EntryType entryTypeFromChange(Change<?> change) {
        return change.type().contentType().isAssignableFrom(JsonNode.class) ? EntryType.JSON : EntryType.TEXT;
    }

    private CompletionStage<Revision> push(long commitTimeMills, Author author, Repository repository,
                                           Revision revision, CommitMessageDto commitMessage,
                                           Iterable<Change<?>> changes) {
        final String summary = commitMessage.summary();
        final String detail = commitMessage.detail();
        final Markup markup = commitMessage.markup();

        return repository.normalize(revision).thenCompose(normalizedRevision -> execute(
                Command.push(commitTimeMills, author, repository.parent().name(), repository.name(),
                             normalizedRevision,
                             summary, detail, markup, changes)));
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/contents{path}?revision={revision}&
     * queryType={queryType}&expression={expression}
     *
     * <p>Returns the entry of files in the path. This is same with
     * {@link #listFiles(String, String, Repository)} except that containing the content of the files.
     * Note that if the {@link HttpHeaderNames#IF_NONE_MATCH} in which has a revision is sent with,
     * this will await for the time specified in {@link HttpHeaderNames#PREFER}.
     * During the time if the specified revision becomes different with the latest revision, this will
     * response back right away to the client with the {@link WatchResultDto}.
     * {@link HttpStatus#NOT_MODIFIED} otherwise.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repos/(?<repoName>[^/]+)/contents(?<path>(|/.*))$")
    @Decorator(ProjectMembersOnly.class)
    public CompletionStage<?> getFiles(@Param("path") String path,
                                       @Param("revision") @Default("-1") String revision,
                                       @RequestObject Repository repository,
                                       @RequestObject(WatchRequestConverter.class)
                                               Optional<WatchRequest> watchRequest,
                                       @RequestObject(RequestQueryConverter.class) Optional<Query<?>> query) {
        final String path0 = rootDirIfEmpty(path);

        // watch repository or a file
        if (watchRequest.isPresent()) {
            final Revision lastKnownRevision = watchRequest.get().lastKnownRevision();
            long timeOutMillis = watchRequest.get().timeoutMillis();
            if (query.isPresent()) {
                return watchFile(repository, lastKnownRevision, query.get(), timeOutMillis);
            }

            return watchRepository(repository, lastKnownRevision, path0, timeOutMillis);
        }

        if (query.isPresent()) {
            // get a file
            return repository.get(new Revision(revision), query.get())
                             .handle(returnOrThrow((QueryResult<?> result) ->
                                                           DtoConverter.convert(result, path0)));
        }

        // get files
        return listFiles(repository, path0, new Revision(revision), ImmutableMap.of());
    }

    private CompletionStage<?> watchFile(Repository repository, Revision lastKnownRevision,
                                         Query<?> query, long timeOutMillis) {
        final CompletableFuture<? extends QueryResult<?>> future = watchService.watchFile(
                repository, lastKnownRevision, query, timeOutMillis);

        return future.thenCompose(result -> handleWatchSuccess(repository, result.revision(), query.path()))
                     .exceptionally(this::handleWatchFailure);
    }

    private static CompletionStage<Object> handleWatchSuccess(Repository repository,
                                                              Revision revision, String pathPattern) {
        final CompletableFuture<List<Commit>> historyFuture =
                repository.history(revision, revision, pathPattern);
        return repository.find(revision, pathPattern)
                         .thenCombine(historyFuture, (entryMap, commits) -> {
                             // the size of commits should be 1
                             return DtoConverter.convert(commits.get(0), entryMap.values(),
                                                         repository.parent().name(), repository.name());
                         });
    }

    private Object handleWatchFailure(Throwable thrown) {
        if (Throwables.getRootCause(thrown) instanceof CancellationException &&
            !watchService.isServerStopping()) {

            // timeout happens
            return HttpResponse.of(HttpStatus.NOT_MODIFIED);
        }
        return Exceptions.throwUnsafely(thrown);
    }

    private CompletionStage<?> watchRepository(Repository repository, Revision lastKnownRevision,
                                               String path, long timeOutMillis) {
        final String pathPattern = appendWildCardIfDirectory(path);
        final CompletableFuture<Revision> future =
                watchService.watchRepository(repository, lastKnownRevision, pathPattern, timeOutMillis);

        return future.thenCompose(revision -> handleWatchSuccess(repository, revision, pathPattern))
                     .exceptionally(this::handleWatchFailure);
    }

    /**
     * DELETE /projects/{projectName}/repos/{repoName}/contents{path}?revision={revision}
     *
     * <p>Deletes a file.
     */
    @Delete("regex:/projects/(?<projectName>[^/]+)/repos/(?<repoName>[^/]+)/contents(?<path>(|/.*))$")
    @Decorator(ProjectMembersOnly.class)
    public CompletionStage<Void> deleteFile(@Param("path") String path,
                                            @Param("revision") @Default("-1") String revision,
                                            @RequestObject Repository repository,
                                            @RequestObject JsonNode node,
                                            @RequestObject Author author) {
        final String path0 = rootDirIfEmpty(path);

        checkArgument(node.get("commitMessage") != null, "commit message should be non-null");
        final JsonNode commitMessageNode = node.get("commitMessage");

        final CommitMessageDto commitMessage = convertCommitMessage(commitMessageNode);
        final long commitTimeMillis = System.currentTimeMillis();

        return push(commitTimeMillis, author, repository, new Revision(revision),
                    commitMessage, ImmutableList.of(Change.ofRemoval(path0)))
                .handle(HttpApiUtil::throwUnsafelyIfNonNull);
    }

    /**
     * PATCH /projects/{projectName}/repos/{repoName}/contents{path}?revision={revision}
     *
     * <p>Patches a file with the JSON_PATCH.
     */
    @ConsumeType("application/json-patch+json")
    @Patch("regex:/projects/(?<projectName>[^/]+)/repos/(?<repoName>[^/]+)/contents(?<path>(|/.*))$")
    @Decorator(ProjectMembersOnly.class)
    public CompletionStage<EntryDto<?>> patchFile(@Param("path") String path,
                                                  @Param("revision") @Default("-1") String revision,
                                                  @RequestObject Repository repository,
                                                  @RequestObject JsonNode node,
                                                  @RequestObject Author author) {
        final String path0 = rootDirIfEmpty(path);

        final Entry<CommitMessageDto, Change<?>> commitMessageAndChange = commitMessageAndPatch(path0, node);
        final CommitMessageDto commitMessage = commitMessageAndChange.getKey();
        final Change<?> change = commitMessageAndChange.getValue();
        final long commitTimeMillis = System.currentTimeMillis();

        return push(commitTimeMillis, author, repository, new Revision(revision),
                    commitMessage, ImmutableList.of(change))
                .handle(returnOrThrow((Revision resultRevision) -> DtoConverter.convert(
                        resultRevision, repository.parent().name(), repository.name(),
                        path0, entryTypeFromChange(change), commitTimeMillis)));
    }

    private static Entry<CommitMessageDto, Change<?>> commitMessageAndPatch(String path, JsonNode node) {
        checkArgument(node.get("patch") != null && node.get("commitMessage") != null,
                      "patch and commit message should be non-null");

        return Maps.immutableEntry(convertCommitMessage(node.get("commitMessage")),
                                   getChangeOfPatch(path, node.get("patch")));
    }

    private static Change<?> getChangeOfPatch(String path, JsonNode jsonNode) {
        validateFilePath(path, "path");

        if (isValidJsonFilePath(path)) {
            return Change.ofJsonPatch(path, jsonNode);
        }

        checkArgument(jsonNode.get(0) != null && jsonNode.get(0).get("value") != null &&
                      !isNullOrEmpty(jsonNode.get(0).get("value").textValue()),
                      "text patch should be non-null");
        return Change.ofTextPatch(path, jsonNode.get(0).get("value").textValue());
    }

    private Repository getRepository(String projectName, String repoName) {
        checkRepositoryExists(projectName, repoName);
        return projectManager().get(projectName).repos().get(repoName);
    }

    private void checkRepositoryExists(String projectName, String repoName) {
        if (!projectManager().exists(projectName)) {
            throw newHttpResponseException(HttpStatus.NOT_FOUND, "project " + projectName + " not found");
        }

        if (!projectManager().get(projectName).repos().exists(repoName)) {
            throw newHttpResponseException(HttpStatus.NOT_FOUND, "repository " + repoName + " not found");
        }
    }
}
