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

import static com.linecorp.centraldogma.internal.Util.unsafeCast;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.google.common.base.Throwables;
import com.spotify.futures.CompletableFutures;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.server.internal.storage.StorageException;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.FindOption;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

final class CachingRepository implements Repository {

    private final Repository repo;

    @SuppressWarnings("rawtypes")
    private final AsyncLoadingCache<CacheableCall, Object> cache;

    private final Commit firstCommit;

    CachingRepository(Repository repo, RepositoryCache cache) {
        this.repo = requireNonNull(repo, "repo");
        this.cache = requireNonNull(cache, "cache").cache;

        try {
            final List<Commit> history = repo.history(Revision.INIT, Revision.INIT, ALL_PATH, 1).join();
            firstCommit = history.get(0);
        } catch (CompletionException e) {
            final Throwable cause = Throwables.getRootCause(e);
            Throwables.throwIfUnchecked(cause);
            throw new StorageException("failed to retrieve the initial commit", cause);
        }
    }

    @Override
    public long creationTimeMillis() {
        return firstCommit.when();
    }

    @Override
    public Author author() {
        return firstCommit.author();
    }

    @Override
    public <T> CompletableFuture<QueryResult<T>> getOrElse(Revision revision, Query<T> query,
                                                           @Nullable QueryResult<T> other) {
        requireNonNull(revision, "revision");
        requireNonNull(query, "query");

        final CompletableFuture<Object> future = normalizeAndCompose(
                revision,
                rev -> cache.get(new CacheableQueryCall(repo, rev, query))
                            .thenApply(result -> result != CacheableQueryCall.EMPTY ? result : other));
        return unsafeCast(future);
    }

    @Override
    public CompletableFuture<Map<String, Entry<?>>> find(Revision revision, String pathPattern,
                                                         Map<FindOption<?>, ?> options) {
        requireNonNull(revision, "revision");
        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(options, "options");

        final CompletableFuture<Object> future = normalizeAndCompose(
                revision,
                rev -> cache.get(new CacheableFindCall(repo, rev, pathPattern, options)));

        return unsafeCast(future);
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

        final RevisionRange range;
        try {
            range = normalizeNow(from, to);
        } catch (Exception e) {
            return CompletableFutures.exceptionallyCompletedFuture(e);
        }

        // Make sure maxCommits do not exceed its theoretical limit to increase the chance of cache hit.
        // e.g. when from = 2 and to = 4, the same result should be yielded when maxCommits >= 3.
        final int actualMaxCommits = Math.min(
                maxCommits, Math.abs(range.from().major() - range.to().major()) + 1);
        return unsafeCast(cache.get(
                new CacheableHistoryCall(repo, range.from(), range.to(), pathPattern, actualMaxCommits)));
    }

    @Override
    public CompletableFuture<Change<?>> diff(Revision from, Revision to, Query<?> query) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        requireNonNull(query, "query");

        final RevisionRange range;
        try {
            range = normalizeNow(from, to).toAscending();
        } catch (Exception e) {
            return CompletableFutures.exceptionallyCompletedFuture(e);
        }

        return unsafeCast(cache.get(new CacheableSingleDiffCall(repo, range.from(), range.to(), query)));
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> diff(Revision from, Revision to, String pathPattern) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        requireNonNull(pathPattern, "pathPattern");

        final RevisionRange range;
        try {
            range = normalizeNow(from, to).toAscending();
        } catch (Exception e) {
            return CompletableFutures.exceptionallyCompletedFuture(e);
        }

        return unsafeCast(cache.get(new CacheableMultiDiffCall(repo, range.from(), range.to(), pathPattern)));
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
    public CompletableFuture<Revision> commit(Revision baseRevision, long commitTimeMillis,
                                              Author author, String summary, String detail, Markup markup,
                                              Iterable<Change<?>> changes) {

        return repo.commit(baseRevision, commitTimeMillis, author, summary, detail, markup, changes);
    }

    @Override
    public CompletableFuture<Revision> watch(Revision lastKnownRevision, String pathPattern) {
        return repo.watch(lastKnownRevision, pathPattern);
    }

    private <T> CompletableFuture<T> normalizeAndCompose(
            Revision revision, Function<Revision, CompletableFuture<T>> function) {
        return normalize(revision).thenCompose(function);
    }
}
