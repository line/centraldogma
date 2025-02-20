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

package com.linecorp.centraldogma.server.internal.storage.repository.cache;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.linecorp.centraldogma.internal.Util.unsafeCast;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.throwUnsafelyIfNonNull;
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ONE_WITH_CONTENT;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryType;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.command.ContentTransformer;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.CacheableCall;
import com.linecorp.centraldogma.server.storage.repository.DiffResultType;
import com.linecorp.centraldogma.server.storage.repository.FindOption;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryListener;

final class CachingRepository implements Repository {

    private static final CancellationException CANCELLATION_EXCEPTION =
            Exceptions.clearTrace(new CancellationException("watch cancelled by caller"));

    private final Repository repo;
    private final RepositoryCache cache;

    CachingRepository(Repository repo, RepositoryCache cache) {
        this.repo = requireNonNull(repo, "repo");
        this.cache = requireNonNull(cache, "cache");
    }

    @Override
    public org.eclipse.jgit.lib.Repository jGitRepository() {
        return repo.jGitRepository();
    }

    @Override
    public long creationTimeMillis() {
        return repo.creationTimeMillis();
    }

    @Override
    public Author author() {
        return repo.author();
    }

    @Override
    public <T> CompletableFuture<Entry<T>> getOrNull(Revision revision, Query<T> query) {
        requireNonNull(revision, "revision");
        requireNonNull(query, "query");

        final Revision normalizedRevision = normalizeNow(revision);
        if (query.type() == QueryType.IDENTITY || query.type() == QueryType.IDENTITY_TEXT ||
            query.type() == QueryType.IDENTITY_JSON) {
            // If the query is an IDENTITY type, call find() so that the caches are reused in one place when
            // calls getOrNull(), find() and mergeFiles().
            final String path = query.path();
            final CompletableFuture<Entry<?>> future =
                    find(revision, path, FIND_ONE_WITH_CONTENT).thenApply(findResult -> findResult.get(path));
            return unsafeCast(future);
        }

        final CompletableFuture<Object> future =
                cache.get(new CacheableQueryCall(repo, normalizedRevision, query))
                     .handleAsync((result, cause) -> {
                         throwUnsafelyIfNonNull(cause);
                         return result != CacheableQueryCall.EMPTY ? result : null;
                     }, executor());
        return unsafeCast(future);
    }

    @Override
    public CompletableFuture<Map<String, Entry<?>>> find(Revision revision, String pathPattern,
                                                         Map<FindOption<?>, ?> options) {
        requireNonNull(revision, "revision");
        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(options, "options");

        final Revision normalizedRevision = normalizeNow(revision);
        return execute(new CacheableFindCall(repo, normalizedRevision, pathPattern, options));
    }

    @Override
    public CompletableFuture<List<Commit>> history(Revision from, Revision to,
                                                   String pathPattern, int maxCommits) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        requireNonNull(pathPattern, "pathPattern");
        if (maxCommits <= 0) {
            throw new IllegalArgumentException("maxCommits: " + maxCommits + " (expected: > 0)");
        }

        final RevisionRange range = normalizeNow(from, to);

        // Make sure maxCommits do not exceed its theoretical limit to increase the chance of cache hit.
        // e.g. when from = 2 and to = 4, the same result should be yielded when maxCommits >= 3.
        final int actualMaxCommits = Math.min(
                maxCommits, Math.abs(range.from().major() - range.to().major()) + 1);
        return execute(new CacheableHistoryCall(repo, range.from(), range.to(), pathPattern, actualMaxCommits));
    }

    @Override
    public CompletableFuture<Change<?>> diff(Revision from, Revision to, Query<?> query) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        requireNonNull(query, "query");

        final RevisionRange range = normalizeNow(from, to).toAscending();
        return execute(new CacheableSingleDiffCall(repo, range.from(), range.to(), query));
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> diff(Revision from, Revision to, String pathPattern,
                                                          DiffResultType diffResultType) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(diffResultType, "diffResultType");

        final RevisionRange range = normalizeNow(from, to).toAscending();
        return execute(new CacheableMultiDiffCall(repo, range.from(), range.to(), pathPattern, diffResultType));
    }

    @Override
    public CompletableFuture<Revision> findLatestRevision(Revision lastKnownRevision, String pathPattern,
                                                          boolean errorOnEntryNotFound) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        requireNonNull(pathPattern, "pathPattern");

        final RevisionRange range = normalizeNow(lastKnownRevision, Revision.HEAD);
        if (range.from().equals(range.to())) {
            // Empty range.
            return CompletableFuture.completedFuture(null);
        }

        return cache.get(new CacheableFindLatestRevCall(repo, range.from(), range.to(),
                                                        pathPattern, errorOnEntryNotFound))
                    .handleAsync((result, cause) -> {
                        throwUnsafelyIfNonNull(cause);
                        if (result == CacheableFindLatestRevCall.ENTRY_NOT_FOUND) {
                            throw new EntryNotFoundException(range.from(), pathPattern);
                        }
                        if (result == CacheableFindLatestRevCall.EMPTY) {
                            return null;
                        }
                        return result;
                    }, executor());
    }

    @Override
    public CompletableFuture<Revision> watch(Revision lastKnownRevision, String pathPattern,
                                             boolean errorOnEntryNotFound) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        requireNonNull(pathPattern, "pathPattern");

        final CompletableFuture<Revision> latestRevFuture =
                findLatestRevision(lastKnownRevision, pathPattern, errorOnEntryNotFound);
        if (latestRevFuture.isCompletedExceptionally() || latestRevFuture.getNow(null) != null) {
            return latestRevFuture;
        }

        final CompletableFuture<Revision> future = new CompletableFuture<>();
        latestRevFuture.whenComplete((latestRevision, cause) -> {
            if (cause != null) {
                future.completeExceptionally(cause);
                return;
            }

            if (latestRevision != null) {
                future.complete(latestRevision);
                return;
            }

            // Propagate the state of 'watchFuture' to 'future'.
            final CompletableFuture<Revision> watchFuture =
                    repo.watch(lastKnownRevision, pathPattern, errorOnEntryNotFound);
            watchFuture.whenComplete((watchResult, watchCause) -> {
                if (watchCause == null) {
                    future.complete(watchResult);
                } else {
                    future.completeExceptionally(watchCause);
                }
            });

            // Cancel the 'watchFuture' if 'future' is complete. 'future' is complete on the following cases:
            //
            // 1) The state of 'watchFuture' has been propagated to 'future' by the callback we registered
            //    above. In this case, 'watchFuture.completeExceptionally()' call below has no effect because
            //    'watchFuture' is complete already.
            //
            // 2) A user completed 'future' by his or her own, most often for cancellation.
            //    'watchFuture' will be completed by 'watchFuture.completeExceptionally()' below.
            //    The callback we registered to 'watchFuture' above will have no effect because 'future' is
            //    complete already.

            future.whenComplete(
                    (unused1, unused2) -> watchFuture.completeExceptionally(CANCELLATION_EXCEPTION));
        });

        return future;
    }

    @Override
    public <T> CompletableFuture<MergedEntry<T>> mergeFiles(Revision revision, MergeQuery<T> query) {
        requireNonNull(revision, "revision");
        requireNonNull(query, "query");

        final Revision normalizedRevision = normalizeNow(revision);
        return execute(new CacheableMergeQueryCall<>(repo, normalizedRevision, query));
    }

    @Override
    public <T> CompletableFuture<T> execute(CacheableCall<T> cacheableCall) {
        return unsafeCast(cache.get(cacheableCall).handleAsync((result, cause) -> {
            throwUnsafelyIfNonNull(cause);
            return result;
        }, executor()));
    }

    @Override
    public void addListener(RepositoryListener listener) {
        repo.addListener(listener);
    }

    @Override
    public void removeListener(RepositoryListener listener) {
        repo.removeListener(listener);
    }

    private static Executor executor() {
        return RequestContext.mapCurrent(RequestContext::eventLoop, CommonPools.workerGroup()::next);
    }

    // Simple delegations

    @Override
    public Project parent() {
        return repo.parent();
    }

    @Override
    public String name() {
        return repo.name();
    }

    @Override
    public Revision normalizeNow(Revision revision) {
        return repo.normalizeNow(revision);
    }

    @Override
    public RevisionRange normalizeNow(Revision from, Revision to) {
        return repo.normalizeNow(from, to);
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> previewDiff(Revision baseRevision,
                                                                 Iterable<Change<?>> changes) {
        requireNonNull(baseRevision, "baseRevision");
        return repo.previewDiff(baseRevision, changes);
    }

    @Override
    public CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis,
                                                  Author author, String summary, String detail, Markup markup,
                                                  Iterable<Change<?>> changes, boolean normalizing) {

        return repo.commit(baseRevision, commitTimeMillis, author, summary, detail, markup, changes,
                           normalizing);
    }

    @Override
    public CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis, Author author,
                                                  String summary, String detail, Markup markup,
                                                  ContentTransformer<?> transformer) {
        return repo.commit(baseRevision, commitTimeMillis, author, summary, detail, markup, transformer);
    }

    @Override
    public String toString() {
        return toStringHelper(this)
                .add("repo", repo)
                .toString();
    }
}
