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

package com.linecorp.centraldogma.server.internal.storage.repository;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static com.linecorp.centraldogma.internal.Util.unsafeCast;
import static java.util.Objects.requireNonNull;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;

/**
 * Utility methods that are useful when implementing a {@link Repository} implementation.
 */
final class RepositoryUtil {

    static final Map<FindOption<?>, Object> EXISTS_FIND_OPTIONS = new IdentityHashMap<>();
    static final Map<FindOption<?>, Object> GET_FIND_OPTIONS = new IdentityHashMap<>();

    private static final CancellationException CANCELLATION_EXCEPTION =
            Exceptions.clearTrace(new CancellationException("parent complete"));

    static {
        EXISTS_FIND_OPTIONS.put(FindOption.FETCH_CONTENT, false);
        EXISTS_FIND_OPTIONS.put(FindOption.MAX_ENTRIES, 1);

        GET_FIND_OPTIONS.put(FindOption.MAX_ENTRIES, 1);
    }

    static <T> CompletableFuture<QueryResult<T>> watch(Repository repo, Revision lastKnownRev, Query<T> query) {

        requireNonNull(repo, "repo");
        requireNonNull(lastKnownRev, "lastKnownRev");
        requireNonNull(query, "query");

        @SuppressWarnings("unchecked")
        final Query<Object> castQuery = (Query<Object>) query;
        final CompletableFuture<QueryResult<Object>> parentFuture = new CompletableFuture<>();
        repo.getOrElse(lastKnownRev, castQuery, null)
            .thenAccept(oldResult -> watch(repo, castQuery, lastKnownRev, oldResult, parentFuture))
            .exceptionally(voidFunction(parentFuture::completeExceptionally));

        return unsafeCast(parentFuture);
    }

    private static void watch(Repository repo, Query<Object> query,
                              Revision lastKnownRev, @Nullable QueryResult<Object> oldResult,
                              CompletableFuture<QueryResult<Object>> parentFuture) {

        final CompletableFuture<Revision> future = repo.watch(lastKnownRev, query.path());
        parentFuture.whenComplete((res, cause) -> future.completeExceptionally(CANCELLATION_EXCEPTION));

        future.thenCompose(newRev -> {
            //noinspection CodeBlock2Expr (too long to make an expression)
            return repo.getOrElse(newRev, query, null)
                       .thenApply(queryResult -> new RevisionAndQueryResult(newRev, queryResult));
        }).thenAccept(res -> {
            final Revision newRev = res.revision;
            final QueryResult<Object> newResult = res.queryResult;

            if (newResult == null) {
                // Removed
                parentFuture.complete(new QueryResult<>(newRev, null, null));
                return;
            }

            if (oldResult != null && Objects.equals(oldResult.content(), newResult.content())) {
                // The commit did not change the query result; watch again for more changes.
                if (!parentFuture.isDone()) {
                    // ... only when the parent future has not been cancelled.
                    watch(repo, query, newRev, oldResult, parentFuture);
                }
            } else {
                parentFuture.complete(newResult);
            }
        }).exceptionally(voidFunction(parentFuture::completeExceptionally));
    }

    private RepositoryUtil() {}

    private static final class RevisionAndQueryResult {
        final Revision revision;
        final QueryResult<Object> queryResult;

        RevisionAndQueryResult(Revision revision, QueryResult<Object> queryResult) {
            this.revision = revision;
            this.queryResult = queryResult;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("revision", revision)
                              .add("queryResult", queryResult)
                              .toString();
        }
    }
}
