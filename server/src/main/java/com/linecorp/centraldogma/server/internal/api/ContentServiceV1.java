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
import static com.linecorp.centraldogma.common.EntryType.DIRECTORY;
import static com.linecorp.centraldogma.internal.Util.isValidDirPath;
import static com.linecorp.centraldogma.internal.Util.isValidFilePath;
import static com.linecorp.centraldogma.server.internal.api.DtoConverter.convert;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.returnOrThrow;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.internal.api.v1.ChangeDto;
import com.linecorp.centraldogma.internal.api.v1.CommitMessageDto;
import com.linecorp.centraldogma.internal.api.v1.EntryDto;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.internal.api.v1.WatchResultDto;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresReadPermission;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresWritePermission;
import com.linecorp.centraldogma.server.internal.api.converter.ChangesRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.CommitMessageRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.MergeQueryRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.QueryRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.WatchRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.WatchRequestConverter.WatchRequest;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.FindOption;
import com.linecorp.centraldogma.server.storage.repository.FindOptions;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * Annotated service object for managing and watching contents.
 */
@ProducesJson
@RequiresReadPermission
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
     * GET /projects/{projectName}/repos/{repoName}/list{path}?revision={revision}
     *
     * <p>Returns the list of files in the path.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repos/(?<repoName>[^/]+)/list(?<path>(|/.*))$")
    public CompletableFuture<List<EntryDto<?>>> listFiles(@Param("path") String path,
                                                          @Param("revision") @Default("-1") String revision,
                                                          Repository repository) {
        final String normalizedPath = normalizePath(path);
        final Revision normalizedRev = repository.normalizeNow(new Revision(revision));
        final CompletableFuture<List<EntryDto<?>>> future = new CompletableFuture<>();
        listFiles(repository, normalizedPath, normalizedRev, false, future);
        return future;
    }

    private static void listFiles(Repository repository, String pathPattern, Revision normalizedRev,
                                  boolean withContent, CompletableFuture<List<EntryDto<?>>> result) {
        final Map<FindOption<?>, ?> options = withContent ? FindOptions.FIND_ALL_WITH_CONTENT
                                                          : FindOptions.FIND_ALL_WITHOUT_CONTENT;

        repository.find(normalizedRev, pathPattern, options).handle((entries, thrown) -> {
            if (thrown != null) {
                result.completeExceptionally(thrown);
                return null;
            }
            // If the pathPattern is a valid file path and the result is a directory, the client forgets to add
            // "/*" to the end of the path. So, let's do it and invoke once more.
            // This is called once at most, because the pathPattern is not a valid file path anymore.
            if (isValidFilePath(pathPattern) && entries.size() == 1 &&
                entries.values().iterator().next().type() == DIRECTORY) {
                listFiles(repository, pathPattern + "/*", normalizedRev, withContent, result);
            } else {
                result.complete(entries.values().stream()
                                       .map(entry -> convert(repository, normalizedRev, entry, withContent))
                                       .collect(toImmutableList()));
            }
            return null;
        });
    }

    /**
     * Normalizes the path according to the following order.
     * <ul>
     *   <li>if the path is {@code null}, empty string or "/", normalize to {@code "/*"}</li>
     *   <li>if the path is a valid file path, return the path as it is</li>
     *   <li>if the path is a valid directory path, append "*" at the end</li>
     * </ul>
     */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/*";
        }
        if (isValidFilePath(path)) {
            return path;
        }
        if (isValidDirPath(path)) {
            if (path.endsWith("/")) {
                return path + '*';
            } else {
                return path + "/*";
            }
        }
        return path;
    }

    /**
     * POST /projects/{projectName}/repos/{repoName}/contents?revision={revision}
     *
     * <p>Pushes a commit.
     */
    @Post("/projects/{projectName}/repos/{repoName}/contents")
    @RequiresWritePermission
    public CompletableFuture<PushResultDto> push(
            @Param("revision") @Default("-1") String revision,
            Repository repository,
            Author author,
            CommitMessageDto commitMessage,
            @RequestConverter(ChangesRequestConverter.class) Iterable<Change<?>> changes) {

        final long commitTimeMillis = System.currentTimeMillis();
        return push(commitTimeMillis, author, repository, new Revision(revision), commitMessage, changes)
                .toCompletableFuture()
                .thenApply(rrev -> convert(rrev, commitTimeMillis));
    }

    private CompletableFuture<Revision> push(long commitTimeMills, Author author, Repository repository,
                                             Revision revision, CommitMessageDto commitMessage,
                                             Iterable<Change<?>> changes) {
        final String summary = commitMessage.summary();
        final String detail = commitMessage.detail();
        final Markup markup = commitMessage.markup();

        return execute(Command.push(
                commitTimeMills, author, repository.parent().name(), repository.name(),
                revision, summary, detail, markup, changes));
    }

    /**
     * POST /projects/{projectName}/repos/{repoName}/preview?revision={revision}
     *
     * <p>Previews the actual changes which will be resulted by the given changes.
     */
    @Post("/projects/{projectName}/repos/{repoName}/preview")
    public CompletableFuture<Iterable<ChangeDto<?>>> preview(
            @Param("revision") @Default("-1") String revision,
            Repository repository,
            @RequestConverter(ChangesRequestConverter.class) Iterable<Change<?>> changes) {

        final CompletableFuture<Map<String, Change<?>>> changesFuture =
                repository.previewDiff(new Revision(revision), changes);

        return changesFuture.thenApply(previewDiffs -> previewDiffs.values().stream()
                                                                   .map(DtoConverter::convert)
                                                                   .collect(toImmutableList()));
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
     * response back right away to the client.
     * {@link HttpStatus#NOT_MODIFIED} otherwise.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repos/(?<repoName>[^/]+)/contents(?<path>(|/.*))$")
    public CompletableFuture<?> getFiles(
            ServiceRequestContext ctx,
            @Param("path") String path, @Param("revision") @Default("-1") String revision,
            Repository repository,
            @RequestConverter(WatchRequestConverter.class) Optional<WatchRequest> watchRequest,
            @RequestConverter(QueryRequestConverter.class) Optional<Query<?>> query) {
        final String normalizedPath = normalizePath(path);

        // watch repository or a file
        if (watchRequest.isPresent()) {
            final Revision lastKnownRevision = watchRequest.get().lastKnownRevision();
            final long timeOutMillis = watchRequest.get().timeoutMillis();
            if (query.isPresent()) {
                return watchFile(ctx, repository, lastKnownRevision, query.get(), timeOutMillis);
            }

            return watchRepository(ctx, repository, lastKnownRevision, normalizedPath, timeOutMillis);
        }

        final Revision normalizedRev = repository.normalizeNow(new Revision(revision));
        if (query.isPresent()) {
            // get a file
            return repository.get(normalizedRev, query.get())
                             .handle(returnOrThrow((Entry<?> result) -> convert(repository, normalizedRev,
                                                                                result, true)));
        }

        // get files
        final CompletableFuture<List<EntryDto<?>>> future = new CompletableFuture<>();
        listFiles(repository, normalizedPath, normalizedRev, true, future);
        return future;
    }

    private CompletableFuture<?> watchFile(ServiceRequestContext ctx,
                                           Repository repository, Revision lastKnownRevision,
                                           Query<?> query, long timeOutMillis) {
        final CompletableFuture<? extends Entry<?>> future = watchService.watchFile(
                repository, lastKnownRevision, query, timeOutMillis);

        if (!future.isDone()) {
            ctx.log().whenComplete().thenRun(() -> future.cancel(false));
        }

        return future.thenApply(entry -> {
            final Revision revision = entry.revision();
            final EntryDto<?> entryDto = convert(repository, revision, entry, true);
            return (Object) new WatchResultDto(revision, entryDto);
        }).exceptionally(ContentServiceV1::handleWatchFailure);
    }

    private CompletableFuture<?> watchRepository(ServiceRequestContext ctx,
                                                 Repository repository, Revision lastKnownRevision,
                                                 String pathPattern, long timeOutMillis) {
        final CompletableFuture<Revision> future =
                watchService.watchRepository(repository, lastKnownRevision, pathPattern, timeOutMillis);

        if (!future.isDone()) {
            ctx.log().whenComplete().thenRun(() -> future.cancel(false));
        }

        return future.thenApply(revision -> (Object) new WatchResultDto(revision, null))
                     .exceptionally(ContentServiceV1::handleWatchFailure);
    }

    private static Object handleWatchFailure(Throwable thrown) {
        if (Throwables.getRootCause(thrown) instanceof CancellationException) {
            // timeout happens
            return HttpResponse.of(HttpStatus.NOT_MODIFIED);
        }
        return Exceptions.throwUnsafely(thrown);
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/commits/{revision}?
     * path={path}&amp;to={to}&amp;maxCommits={maxCommits}
     *
     * <p>Returns a commit or the list of commits in the path. If the user specify the {@code revision} only,
     * this will return the corresponding commit. If the user does not specify the {@code revision} or
     * specify {@code to}, this will return the list of commits.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repos/(?<repoName>[^/]+)/commits(?<revision>(|/.*))$")
    public CompletableFuture<?> listCommits(@Param("revision") String revision,
                                            @Param("path") @Default("/**") String path,
                                            @Param("to") Optional<String> to,
                                            @Param("maxCommits") Optional<Integer> maxCommits,
                                            Repository repository) {
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

        final RevisionRange range = repository.normalizeNow(fromRevision, toRevision).toDescending();
        final int maxCommits0 = maxCommits.map(integer -> Math.min(integer, Repository.DEFAULT_MAX_COMMITS))
                                          .orElse(Repository.DEFAULT_MAX_COMMITS);
        return repository
                .history(range.from(), range.to(), normalizePath(path), maxCommits0)
                .thenApply(commits -> {
                    final boolean toList = isNullOrEmpty(revision) || "/".equalsIgnoreCase(revision) ||
                                           to.isPresent();
                    return objectOrList(commits, toList, DtoConverter::convert);
                });
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/compare?
     * path={path}&amp;from={from}&amp;to={to}&amp;jsonpath={jsonpath} returns a diff.
     *
     * <p>or,
     *
     * <p>GET /projects/{projectName}/repos/{repoName}/compare?
     * pathPattern={pathPattern}&amp;from={from}&amp;to={to} returns diffs.
     */
    @Get("/projects/{projectName}/repos/{repoName}/compare")
    public CompletableFuture<?> getDiff(
            @Param("pathPattern") @Default("/**") String pathPattern,
            @Param("from") @Default("1") String from, @Param("to") @Default("head") String to,
            Repository repository,
            @RequestConverter(QueryRequestConverter.class) Optional<Query<?>> query) {
        if (query.isPresent()) {
            return repository.diff(new Revision(from), new Revision(to), query.get())
                             .thenApply(DtoConverter::convert);
        }
        return repository
                .diff(new Revision(from), new Revision(to), normalizePath(pathPattern))
                .thenApply(changeMap -> changeMap.values().stream()
                                                 .map(DtoConverter::convert).collect(toImmutableList()));
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

    /**
     * GET /projects/{projectName}/repos/{repoName}/merge?
     * revision={revision}&amp;path={path}&amp;optional_path={optional_path}
     *
     * <p>Returns a merged entry of files which are specified in the query string.
     */
    @Get("/projects/{projectName}/repos/{repoName}/merge")
    public <T> CompletableFuture<?> mergeFiles(
            @Param("revision") @Default("-1") String revision, Repository repository,
            @RequestConverter(MergeQueryRequestConverter.class) MergeQuery<T> query) {
        return repository.mergeFiles(new Revision(revision), query).thenApply(DtoConverter::convert);
    }
}
