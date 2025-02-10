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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.common.EntryType.DIRECTORY;
import static com.linecorp.centraldogma.internal.Util.isValidDirPath;
import static com.linecorp.centraldogma.internal.Util.isValidFilePath;
import static com.linecorp.centraldogma.server.internal.api.DtoConverter.convert;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.returnOrThrow;
import static com.linecorp.centraldogma.server.internal.api.RepositoryServiceV1.increaseCounterIfOldRevisionUsed;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.isMetaFile;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.isMirrorFile;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.InvalidPushException;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.internal.api.v1.ChangeDto;
import com.linecorp.centraldogma.internal.api.v1.CommitMessageDto;
import com.linecorp.centraldogma.internal.api.v1.EntryDto;
import com.linecorp.centraldogma.internal.api.v1.MergedEntryDto;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.internal.api.v1.WatchResultDto;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRepositoryRole;
import com.linecorp.centraldogma.server.internal.api.converter.ChangesRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.CommitMessageRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.MergeQueryRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.QueryRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.WatchRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.WatchRequestConverter.WatchRequest;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.FindOption;
import com.linecorp.centraldogma.server.storage.repository.FindOptions;
import com.linecorp.centraldogma.server.storage.repository.Repository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Annotated service object for managing and watching contents.
 */
@ProducesJson
@RequiresRepositoryRole(RepositoryRole.READ)
@RequestConverter(CommitMessageRequestConverter.class)
public class ContentServiceV1 extends AbstractService {

    private static final String MIRROR_LOCAL_REPO = "localRepo";

    private final WatchService watchService;
    private final MeterRegistry meterRegistry;

    public ContentServiceV1(CommandExecutor executor, WatchService watchService, MeterRegistry meterRegistry) {
        super(executor);
        this.watchService = requireNonNull(watchService, "watchService");
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/list{path}?revision={revision}
     *
     * <p>Returns the list of files in the path.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repos/(?<repoName>[^/]+)/list(?<path>(|/.*))$")
    public CompletableFuture<List<EntryDto<?>>> listFiles(ServiceRequestContext ctx,
                                                          @Param String path,
                                                          @Param @Default("-1") String revision,
                                                          @Param @Default("-1") int includeLastFileRevision,
                                                          Repository repository) {
        final String normalizedPath = normalizePath(path);
        final Revision normalizedRev = repository.normalizeNow(new Revision(revision));
        increaseCounterIfOldRevisionUsed(ctx, repository, normalizedRev);
        final CompletableFuture<List<EntryDto<?>>> future = new CompletableFuture<>();
        listFiles(repository, normalizedPath, normalizedRev, false, includeLastFileRevision, future);
        return future;
    }

    private static void listFiles(Repository repository, String pathPattern, Revision normalizedRev,
                                  boolean withContent, int includeLastFileRevision,
                                  CompletableFuture<List<EntryDto<?>>> result) {
        final Map<FindOption<?>, ?> options;
        if (includeLastFileRevision <= 1) {
            options = withContent ? FindOptions.FIND_ALL_WITH_CONTENT
                                  : FindOptions.FIND_ALL_WITHOUT_CONTENT;
        } else {
            options = ImmutableMap.of(FindOption.FETCH_LAST_FILE_REVISION, includeLastFileRevision,
                                      FindOption.FETCH_CONTENT, withContent);
        }

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
                listFiles(repository, pathPattern + "/*", normalizedRev, withContent, includeLastFileRevision,
                          result);
            } else {
                result.complete(entries.values().stream()
                                       .map(entry -> convert(repository, entry, withContent))
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
    @ConsumesJson
    @RequiresRepositoryRole(RepositoryRole.WRITE)
    public CompletableFuture<PushResultDto> push(
            ServiceRequestContext ctx,
            @Param @Default("-1") String revision,
            Repository repository,
            Author author,
            CommitMessageDto commitMessage,
            @RequestConverter(ChangesRequestConverter.class) Iterable<Change<?>> changes) {
        final User user = AuthUtil.currentUser(ctx);
        checkPush(repository.name(), changes, user.isSystemAdmin());
        meterRegistry.counter("commits.push",
                              "project", repository.parent().name(),
                              "repository", repository.name())
                     .increment();

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
                revision, summary, detail, markup, changes)).thenApply(CommitResult::revision);
    }

    /**
     * POST /projects/{projectName}/repos/{repoName}/preview?revision={revision}
     *
     * <p>Previews the actual changes which will be resulted by the given changes.
     */
    @Post("/projects/{projectName}/repos/{repoName}/preview")
    @ConsumesJson
    public CompletableFuture<Iterable<ChangeDto<?>>> preview(
            ServiceRequestContext ctx,
            @Param @Default("-1") String revision,
            Repository repository,
            @RequestConverter(ChangesRequestConverter.class) Iterable<Change<?>> changes) {
        final Revision baseRevision = new Revision(revision);
        increaseCounterIfOldRevisionUsed(ctx, repository, baseRevision);
        final CompletableFuture<Map<String, Change<?>>> changesFuture =
                repository.previewDiff(baseRevision, changes);

        return changesFuture.thenApply(previewDiffs -> previewDiffs.values().stream()
                                                                   .map(DtoConverter::convert)
                                                                   .collect(toImmutableList()));
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/contents{path}?revision={revision}&amp;
     * jsonpath={jsonpath}
     *
     * <p>Returns the entry of files in the path. This is same with
     * {@link #listFiles(ServiceRequestContext, String, String, int, Repository)} except that containing
     * the content of the files.
     * Note that if the {@link HttpHeaderNames#IF_NONE_MATCH} in which has a revision is sent with,
     * this will await for the time specified in {@link HttpHeaderNames#PREFER}.
     * During the time if the specified revision becomes different with the latest revision, this will
     * response back right away to the client.
     * {@link HttpStatus#NOT_MODIFIED} otherwise.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repos/(?<repoName>[^/]+)/contents(?<path>(|/.*))$")
    public CompletableFuture<?> getFiles(
            ServiceRequestContext ctx,
            @Param String path, @Param @Default("-1") String revision,
            @Param @Default("-1") int includeLastFileRevision,
            Repository repository,
            @RequestConverter(WatchRequestConverter.class) @Nullable WatchRequest watchRequest,
            @RequestConverter(QueryRequestConverter.class) @Nullable Query<?> query) {
        increaseCounterIfOldRevisionUsed(ctx, repository, new Revision(revision));
        final String normalizedPath = normalizePath(path);

        // watch repository or a file
        if (watchRequest != null) {
            final Revision lastKnownRevision = watchRequest.lastKnownRevision();
            final long timeOutMillis = watchRequest.timeoutMillis();
            final boolean errorOnEntryNotFound = watchRequest.notifyEntryNotFound();
            if (query != null) {
                return watchFile(ctx, repository, lastKnownRevision, query, timeOutMillis,
                                 errorOnEntryNotFound);
            }

            return watchRepository(ctx, repository, lastKnownRevision, normalizedPath,
                                   timeOutMillis, errorOnEntryNotFound);
        }

        final Revision normalizedRev = repository.normalizeNow(new Revision(revision));
        if (query != null) {
            // get a file
            return repository.get(normalizedRev, query, includeLastFileRevision)
                             .handle(returnOrThrow((Entry<?> result) -> convert(repository, result, true)));
        }

        // get files
        final CompletableFuture<List<EntryDto<?>>> future = new CompletableFuture<>();
        listFiles(repository, normalizedPath, normalizedRev, true, includeLastFileRevision, future);
        return future;
    }

    private CompletableFuture<?> watchFile(ServiceRequestContext ctx,
                                           Repository repository, Revision lastKnownRevision,
                                           Query<?> query, long timeOutMillis, boolean errorOnEntryNotFound) {
        final CompletableFuture<? extends Entry<?>> future = watchService.watchFile(
                repository, lastKnownRevision, query, timeOutMillis, errorOnEntryNotFound);

        if (!future.isDone()) {
            ctx.log().whenComplete().thenRun(() -> future.cancel(false));
        }

        return future.thenApply(entry -> {
            final EntryDto<?> entryDto = convert(repository, entry, true);
            return (Object) new WatchResultDto(entry.revision(), entryDto);
        }).exceptionally(ContentServiceV1::handleWatchFailure);
    }

    private CompletableFuture<?> watchRepository(ServiceRequestContext ctx,
                                                 Repository repository, Revision lastKnownRevision,
                                                 String pathPattern, long timeOutMillis,
                                                 boolean errorOnEntryNotFound) {
        final CompletableFuture<Revision> future =
                watchService.watchRepository(repository, lastKnownRevision, pathPattern,
                                             timeOutMillis, errorOnEntryNotFound);

        if (!future.isDone()) {
            ctx.log().whenComplete().thenRun(() -> future.cancel(false));
        }

        return future.thenApply(revision -> (Object) new WatchResultDto(revision, null))
                     .exceptionally(ContentServiceV1::handleWatchFailure);
    }

    private static Object handleWatchFailure(Throwable thrown) {
        final Throwable rootCause = Throwables.getRootCause(thrown);
        if (rootCause instanceof CancellationException || rootCause instanceof ShuttingDownException) {
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
    public CompletableFuture<?> listCommits(ServiceRequestContext ctx,
                                            @Param String revision,
                                            @Param @Default("/**") String path,
                                            @Param @Nullable String to,
                                            @Param @Nullable Integer maxCommits,
                                            Repository repository) {
        final Revision fromRevision;
        final Revision toRevision;

        // 1. only the "revision" is specified:       get the "revision" and return just one commit
        // 2. only the "to" is specified:             get from "HEAD" to "to" and return the list
        // 3. the "revision" and "to" is specified:   get from the "revision" to "to" and return the list
        // 4. nothing is specified:                   get from "HEAD" to "INIT" and return the list
        if (isNullOrEmpty(revision) || "/".equalsIgnoreCase(revision)) {
            fromRevision = Revision.HEAD;
            toRevision = to != null ? new Revision(to) : Revision.INIT;
        } else {
            fromRevision = new Revision(revision.substring(1));
            toRevision = to != null ? new Revision(to) : fromRevision;
        }

        final RevisionRange range = repository.normalizeNow(fromRevision, toRevision).toDescending();

        increaseCounterIfOldRevisionUsed(ctx, repository, range.from());
        increaseCounterIfOldRevisionUsed(ctx, repository, range.to());

        final int maxCommits0 = firstNonNull(maxCommits, Repository.DEFAULT_MAX_COMMITS);
        return repository
                .history(range.from(), range.to(), normalizePath(path), maxCommits0)
                .thenApply(commits -> {
                    final boolean toList = to != null ||
                                           isNullOrEmpty(revision) ||
                                           "/".equalsIgnoreCase(revision);
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
            ServiceRequestContext ctx,
            @Param @Default("/**") String pathPattern,
            @Param @Default("1") String from, @Param @Default("head") String to,
            Repository repository,
            @RequestConverter(QueryRequestConverter.class) @Nullable Query<?> query) {
        final Revision fromRevision = new Revision(from);
        final Revision toRevision = new Revision(to);
        increaseCounterIfOldRevisionUsed(ctx, repository, fromRevision);
        increaseCounterIfOldRevisionUsed(ctx, repository, toRevision);
        if (query != null) {
            return repository.diff(fromRevision, toRevision, query)
                             .thenApply(DtoConverter::convert);
        } else {
            return repository
                    .diff(fromRevision, toRevision, normalizePath(pathPattern))
                    .thenApply(changeMap -> changeMap.values().stream()
                                                     .map(DtoConverter::convert).collect(toImmutableList()));
        }
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
    public <T> CompletableFuture<MergedEntryDto<T>> mergeFiles(
            ServiceRequestContext ctx,
            @Param @Default("-1") String revision, Repository repository,
            @RequestConverter(MergeQueryRequestConverter.class) MergeQuery<T> query) {
        final Revision rev = new Revision(revision);
        increaseCounterIfOldRevisionUsed(ctx, repository, rev);
        return repository.mergeFiles(rev, query).thenApply(DtoConverter::convert);
    }

    /**
     * Checks if the commit is for creating a file and raises a {@link InvalidPushException} if the
     * given {@code repoName} field is one of {@code meta} and {@code dogma} which are internal repositories.
     */
    public static void checkPush(String repoName, Iterable<Change<?>> changes, boolean isSystemAdmin) {
        if (Project.REPO_META.equals(repoName)) {
            final boolean hasChangesOtherThanMetaRepoFiles =
                    Streams.stream(changes).anyMatch(change -> !isMetaFile(change.path()));
            if (hasChangesOtherThanMetaRepoFiles) {
                throw new InvalidPushException(
                        "The " + Project.REPO_META + " repository is reserved for internal usage.");
            }

            if (isSystemAdmin) {
                // A system admin may push the legacy files to test the mirror migration.
            } else {
                for (Change<?> change : changes) {
                    // 'mirrors.json' and 'credentials.json' are disallowed to be created or modified.
                    // 'mirrors/{id}.json' and 'credentials/{id}.json' must be used instead.
                    final String path = change.path();
                    if (change.type() == ChangeType.REMOVE) {
                        continue;
                    }
                    if ("/mirrors.json".equals(path)) {
                        throw new InvalidPushException(
                                "'/mirrors.json' file is not allowed to create. " +
                                "Use '/mirrors/{id}.json' file or " +
                                "'/api/v1/projects/{projectName}/mirrors' API instead.");
                    }
                    if ("/credentials.json".equals(path)) {
                        throw new InvalidPushException(
                                "'/credentials.json' file is not allowed to create. " +
                                "Use '/credentials/{id}.json' file or " +
                                "'/api/v1/projects/{projectName}/credentials' API instead.");
                    }
                }
            }

            // TODO(ikhoon): Disallow creating a mirror with the commit API. Mirroring REST API should be used
            //               to validate the input.
            final Optional<String> notAllowedLocalRepo =
                    Streams.stream(changes)
                           .filter(change -> isMirrorFile(change.path()))
                           .filter(change -> change.content() != null)
                           .map(change -> {
                               final Object content = change.content();
                               if (content instanceof JsonNode) {
                                   final JsonNode node = (JsonNode) content;
                                   if (!node.isObject()) {
                                       return null;
                                   }
                                   final JsonNode localRepoNode = node.get(MIRROR_LOCAL_REPO);
                                   if (localRepoNode != null) {
                                       final String localRepo = localRepoNode.textValue();
                                       if (Project.isReservedRepoName(localRepo)) {
                                           return localRepo;
                                       }
                                   }
                               }
                               return null;
                           }).filter(Objects::nonNull).findFirst();
            if (notAllowedLocalRepo.isPresent()) {
                throw new InvalidPushException("invalid " + MIRROR_LOCAL_REPO + ": " +
                                               notAllowedLocalRepo.get());
            }
        }
    }
}
