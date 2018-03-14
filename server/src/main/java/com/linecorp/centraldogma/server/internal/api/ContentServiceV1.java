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

package com.linecorp.centraldogma.server.internal.api;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.returnOrThrow;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.internal.api.v1.CommitMessageDto;
import com.linecorp.centraldogma.internal.api.v1.EntryDto;
import com.linecorp.centraldogma.internal.api.v1.WatchResultDto;
import com.linecorp.centraldogma.server.internal.api.auth.HasReadPermission;
import com.linecorp.centraldogma.server.internal.api.auth.HasWritePermission;
import com.linecorp.centraldogma.server.internal.api.converter.ChangesRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.CommitMessageRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.QueryRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.WatchRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.WatchRequestConverter.WatchRequest;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.FindOption;
import com.linecorp.centraldogma.server.internal.storage.repository.RedundantChangeException;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * Annotated service object for managing and watching contents.
 */
@RequestConverter(CommitMessageRequestConverter.class)
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
    @Decorator(HasReadPermission.class)
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
    @Decorator(HasWritePermission.class)
    public CompletionStage<?> commit(@Param("revision") @Default("-1") String revision,
                                     @RequestObject Repository repository,
                                     @RequestObject Author author,
                                     @RequestObject CommitMessageDto commitMessage,
                                     @RequestObject(ChangesRequestConverter.class)
                                             Iterable<Change<?>> changes) {
        final Revision normalizedRevision = repository.normalizeNow(new Revision(revision));

        final CompletableFuture<Map<String, Change<?>>> changesFuture =
                repository.previewDiff(normalizedRevision, changes);

        return changesFuture.thenCompose(previewDiffs -> {
            final long commitTimeMillis = System.currentTimeMillis();
            if (previewDiffs.isEmpty()) {
                throw new RedundantChangeException();
            }
            final CompletableFuture<Revision> resultRevisionFuture =
                    push(commitTimeMillis, author, repository, normalizedRevision,
                         commitMessage, previewDiffs.values()).toCompletableFuture();
            final String pathPattern = joinPaths(changes);
            final CompletableFuture<Map<String, Entry<?>>> findFuture = resultRevisionFuture.thenCompose(
                    result -> repository.find(result, pathPattern));

            return findFuture.thenApply(entries -> objectOrList(
                    entries.values(), Iterables.size(changes) != 1, (collections) -> convertEntry(
                            resultRevisionFuture.join(), // resultRevisionFuture is already complete
                            repository, collections, commitTimeMillis)));
        });
    }

    private CompletionStage<Revision> push(long commitTimeMills, Author author, Repository repository,
                                           Revision revision, CommitMessageDto commitMessage,
                                           Iterable<Change<?>> changes) {
        final String summary = commitMessage.summary();
        final String detail = commitMessage.detail();
        final Markup markup = commitMessage.markup();

        return execute(Command.push(
                commitTimeMills, author, repository.parent().name(), repository.name(),
                revision, summary, detail, markup, changes));
    }

    private static String joinPaths(Iterable<Change<?>> changes) {
        final StringBuilder sb = new StringBuilder();
        for (Change c : changes) {
            if (c.type() == ChangeType.RENAME) {
                sb.append(c.contentAsText()); // newPath
            } else {
                sb.append(c.path());
            }
            sb.append(',');
        }
        return sb.toString();
    }

    private static EntryDto<?> convertEntry(Revision revision, Repository repository,
                                            Entry<?> entry, long commitTimeMillis) {
        return DtoConverter.convert(revision, repository.parent().name(), repository.name(),
                                    entry.path(), entry.type(), commitTimeMillis);
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/contents{path}?revision={revision}&amp;
     * jsonpath={jsonpath}
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
    @Decorator(HasReadPermission.class)
    public CompletionStage<?> getFiles(@Param("path") String path,
                                       @Param("revision") @Default("-1") String revision,
                                       @RequestObject Repository repository,
                                       @RequestObject(WatchRequestConverter.class)
                                               Optional<WatchRequest> watchRequest,
                                       @RequestObject(QueryRequestConverter.class) Optional<Query<?>> query) {
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
     * GET /projects/{projectName}/repos/{repoName}/commits/{revision}?path={path}&amp;to={to}
     *
     * <p>Returns a commit or the list of commits in the path. If the user specify the {@code revision} only,
     * this will return the corresponding commit. If the user does not specify the {@code revision} or
     * specify {@code to}, this will return the list of commits.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repos/(?<repoName>[^/]+)/commits(?<revision>(|/.*))$")
    @Decorator(HasReadPermission.class)
    public CompletionStage<?> listCommits(@Param("revision") String revision,
                                          @Param("path") @Default("/**") String path,
                                          @Param("to") Optional<String> to,
                                          @RequestObject Repository repository) {
        final Revision fromRevision;
        final Revision toRevision;

        // 1. only the "revision" is specified:       get the "revision" and return just one commit
        // 2. only the "to" is specified:             get from "HEAD" to "to" and return the list
        // 3. the "revision" and "to" is specified:   get from the "revision" to "to" and return the list
        // 4. nothing is specified:                   get from "HEAD" to "INIT" and return the list
        if (isNullOrEmpty(revision) || "/".equalsIgnoreCase(revision)) {
            fromRevision = Revision.HEAD;
            toRevision = to.map(Revision::new).orElse(Revision.INIT);
        } else {
            fromRevision = new Revision(revision.substring(1));
            toRevision = to.map(Revision::new).orElse(fromRevision);
        }

        final RevisionRange range = repository.normalizeNow(fromRevision, toRevision);

        return repository
                .history(range.from(), range.to(), path)
                .thenApply(commits -> {
                    final boolean toList = isNullOrEmpty(revision) || "/".equalsIgnoreCase(revision) ||
                                           to.isPresent();
                    return objectOrList(commits, toList, DtoConverter::convert);
                });
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/compare?
     * path={path}&amp;from={from}&amp;to={to}&amp;jsonpath={jsonpath}
     *
     * <p>Returns the diffs.
     */
    @Get("/projects/{projectName}/repos/{repoName}/compare")
    @Decorator(HasReadPermission.class)
    public CompletionStage<?> getDiff(@Param("path") @Default("/**") String path,
                                      @Param("from") @Default("-1") String from,
                                      @Param("to") @Default("1") String to,
                                      @RequestObject Repository repository,
                                      @RequestObject(QueryRequestConverter.class) Optional<Query<?>> query) {
        if (query.isPresent()) {
            return repository.diff(new Revision(from), new Revision(to), query.get())
                             .thenApply(DtoConverter::convert);
        }
        return repository
                .diff(new Revision(from), new Revision(to), path)
                .thenApply(changeMap -> objectOrList(changeMap.values(), true, DtoConverter::convert));
    }

    private static <T> Object objectOrList(Collection<T> collection, boolean toList, Function<T, ?> converter) {
        if (collection.isEmpty()) {
            return ImmutableList.of();
        }
        if (toList) {
            return collection.stream().map(converter).collect(toImmutableList());
        }
        return converter.apply(Iterables.getOnlyElement(collection));
    }
}
