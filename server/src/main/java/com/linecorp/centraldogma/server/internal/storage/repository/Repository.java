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

import static com.linecorp.centraldogma.internal.Util.unsafeCast;
import static com.linecorp.centraldogma.internal.Util.validateFilePath;
import static com.linecorp.centraldogma.server.internal.storage.repository.RepositoryUtil.EXISTS_FIND_OPTIONS;
import static com.linecorp.centraldogma.server.internal.storage.repository.RepositoryUtil.GET_FIND_OPTIONS;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.spotify.futures.CompletableFutures;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryException;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.server.internal.storage.StorageException;
import com.linecorp.centraldogma.server.internal.storage.project.Project;

/**
 * Revision-controlled filesystem-like repository.
 */
public interface Repository {

    int DEFAULT_MAX_COMMITS = 1024;

    String ALL_PATH = "/**";

    /**
     * Returns the parent {@link Project} of this {@link Repository}.
     */
    Project parent();

    /**
     * Returns the name of this {@link Repository}.
     */
    String name();

    /**
     * Returns the creation time of this {@link Repository}.
     */
    default long creationTimeMillis() {
        try {
            final List<Commit> history = history(Revision.INIT, Revision.INIT, ALL_PATH, 1).join();
            return history.get(0).when();
        } catch (CompletionException e) {
            final Throwable cause = Throwables.getRootCause(e);
            Throwables.throwIfUnchecked(cause);
            throw new StorageException("failed to retrieve the initial commit", cause);
        }
    }

    /**
     * Returns the author who created this {@link Repository}.
     */
    default Author author() {
        try {
            final List<Commit> history = history(Revision.INIT, Revision.INIT, ALL_PATH, 1).join();
            return history.get(0).author();
        } catch (CompletionException e) {
            final Throwable cause = Throwables.getRootCause(e);
            Throwables.throwIfUnchecked(cause);
            throw new StorageException("failed to retrieve the initial commit", cause);
        }
    }

    /**
     * Returns the {@link CompletableFuture} whose value is the absolute {@link Revision} of the
     * specified {@link Revision}.
     *
     * @deprecated Use {@link #normalizeNow(Revision)} instead.
     */
    @Deprecated
    default CompletableFuture<Revision> normalize(Revision revision) {
        try {
            return CompletableFuture.completedFuture(normalizeNow(revision));
        } catch (Exception e) {
            return CompletableFutures.exceptionallyCompletedFuture(e);
        }
    }

    /**
     * Returns the absolute {@link Revision} of the specified {@link Revision}.
     *
     * @throws RevisionNotFoundException if the specified {@link Revision} is not found
     */
    Revision normalizeNow(Revision revision);

    /**
     * Returns a {@link RevisionRange} which contains the absolute {@link Revision}s of the specified
     * {@code from} and {@code to}.
     *
     * @throws RevisionNotFoundException if the specified {@code from} or {@code to} is not found
     */
    RevisionRange normalizeNow(Revision from, Revision to);

    /**
     * Returns {@code true} if and only if an {@link Entry} exists at the specified {@code path}.
     */
    default CompletableFuture<Boolean> exists(Revision revision, String path) {
        validateFilePath(path, "path");
        return find(revision, path, EXISTS_FIND_OPTIONS).thenApply(result -> !result.isEmpty());
    }

    /**
     * Retrieves an {@link Entry} at the specified {@code path}.
     *
     * @throws EntryNotFoundException if there's no entry at the specified {@code path}
     *
     * @see #getOrElse(Revision, String, Entry)
     */
    default CompletableFuture<Entry<?>> get(Revision revision, String path) {
        return getOrElse(revision, path, null).thenApply(entry -> {
            if (entry == null) {
                throw new EntryNotFoundException(revision, path);
            }

            return entry;
        });
    }

    /**
     * Performs the specified {@link Query}.
     *
     * @throws EntryNotFoundException if there's no entry at the path specified in the {@link Query}
     *
     * @see #getOrElse(Revision, Query, QueryResult)
     */
    default <T> CompletableFuture<QueryResult<T>> get(Revision revision, Query<T> query) {
        return getOrElse(revision, query, null).thenApply(res -> {
            if (res == null) {
                throw new EntryNotFoundException(revision, query.path());
            }

            return res;
        });
    }

    /**
     * Retrieves an {@link Entry} at the specified {@code path}.
     *
     * @return the {@link Entry} at the specified {@code path} if exists.
     *         The specified {@code other} if there's no such {@link Entry}.
     *
     * @see #get(Revision, String)
     */
    default CompletableFuture<Entry<?>> getOrElse(Revision revision, String path, @Nullable Entry<?> other) {
        validateFilePath(path, "path");

        return find(revision, path, GET_FIND_OPTIONS).thenApply(findResult -> {
            final Entry<?> entry = findResult.get(path);
            return entry != null ? entry : other;
        });
    }

    /**
     * Performs the specified {@link Query}.
     *
     * @return the {@link QueryResult} on a successful query.
     *         The specified {@code other} on a failure due to missing entry.
     *
     * @see #get(Revision, Query)
     */
    default <T> CompletableFuture<QueryResult<T>> getOrElse(
            Revision revision, Query<T> query, @Nullable QueryResult<T> other) {

        requireNonNull(query, "query");
        requireNonNull(revision, "revision");

        return getOrElse(revision, query.path(), null).thenApply(entry -> {
            if (entry == null) {
                return other;
            }

            final EntryType entryType = entry.type();

            if (!query.type().supportedEntryTypes().contains(entryType)) {
                throw new QueryException("unsupported entry type: " + entryType);
            }

            @SuppressWarnings("unchecked")
            final T entryContent = (T) entry.content();
            try {
                return new QueryResult<>(revision, entryType, query.apply(entryContent));
            } catch (QueryException e) {
                throw e;
            } catch (Exception e) {
                throw new QueryException(e);
            }
        });
    }

    /**
     * Finds the {@link Entry}s that match the specified {@code pathPattern}.
     *
     * @return a {@link Map} whose value is the matching {@link Entry} and key is its path
     */
    default CompletableFuture<Map<String, Entry<?>>> find(Revision revision, String pathPattern) {
        return find(revision, pathPattern, ImmutableMap.of());
    }

    /**
     * Finds the {@link Entry}s that match the specified {@code pathPattern}.
     *
     * @return a {@link Map} whose value is the matching {@link Entry} and key is its path
     */
    CompletableFuture<Map<String, Entry<?>>> find(Revision revision, String pathPattern,
                                                  Map<FindOption<?>, ?> options);

    /**
     * Query a file at two different revisions and return the diff of the two query results.
     */
    default CompletableFuture<Change<?>> diff(Revision from, Revision to, Query<?> query) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        requireNonNull(query, "query");

        final RevisionRange range = normalizeNow(from, to).toAscending();

        final String path = query.path();
        final CompletableFuture<Entry<?>> fromEntryFuture = getOrElse(range.from(), path, null);
        final CompletableFuture<Entry<?>> toEntryFuture = getOrElse(range.to(), path, null);

        final CompletableFuture<Change<?>> future =
                CompletableFutures.combine(fromEntryFuture, toEntryFuture, (fromEntry, toEntry) -> {
                    @SuppressWarnings("unchecked")
                    final Query<Object> castQuery = (Query<Object>) query;

                    // Handle the case where the entry does not exist at 'from' or 'to'.
                    if (fromEntry != null) {
                        if (toEntry == null) {
                            // The entry has been removed.
                            return Change.ofRemoval(path);
                        }
                    } else if (toEntry != null) {
                        // The entry has been created.
                        final EntryType toEntryType = toEntry.type();
                        if (!query.type().supportedEntryTypes().contains(toEntryType)) {
                            throw new QueryException("unsupported entry type: " + toEntryType);
                        }

                        final Object toContent = castQuery.apply(toEntry.content());

                        switch (toEntryType) {
                            case JSON:
                                return Change.ofJsonUpsert(path, (JsonNode) toContent);
                            case TEXT:
                                return Change.ofTextUpsert(path, (String) toContent);
                            default:
                                throw new Error();
                        }
                    } else {
                        // The entry did not exist both at 'from' and 'to'.
                        throw new EntryNotFoundException(path + " (" + from + ", " + to + ')');
                    }

                    // Handle the case where the entry exists both at 'from' and at 'to'.
                    final EntryType entryType = fromEntry.type();
                    if (!query.type().supportedEntryTypes().contains(entryType)) {
                        throw new QueryException("unsupported entry type: " + entryType);
                    }
                    if (entryType != toEntry.type()) {
                        throw new QueryException(
                                "mismatching entry type: " + entryType + " != " + toEntry.type());
                    }

                    final Object fromContent = castQuery.apply(fromEntry.content());
                    final Object toContent = castQuery.apply(toEntry.content());

                    switch (entryType) {
                        case JSON:
                            return Change.ofJsonPatch(path, (JsonNode) fromContent, (JsonNode) toContent);
                        case TEXT:
                            return Change.ofTextPatch(path, (String) fromContent, (String) toContent);
                        default:
                            throw new Error();
                    }
                }).toCompletableFuture();

        return unsafeCast(future);
    }

    CompletableFuture<Map<String, Change<?>>> diff(Revision from, Revision to, String pathPattern);

    /**
     * Generates the preview diff against the specified {@code baseRevision} and {@code changes}.
     */
    default CompletableFuture<Map<String, Change<?>>> previewDiff(Revision baseRevision, Change<?>... changes) {
        requireNonNull(changes, "changes");
        return previewDiff(baseRevision, Arrays.asList(changes));
    }

    CompletableFuture<Map<String, Change<?>>> previewDiff(Revision baseRevision, Iterable<Change<?>> changes);

    /**
     * Adds the specified changes to this {@link Repository}.
     *
     * @return the {@link Revision} of the new {@link Commit}
     */
    default CompletableFuture<Revision> commit(Revision baseRevision, long commitTimeMillis,
                                               Author author, String summary, Iterable<Change<?>> changes) {
        return commit(baseRevision, commitTimeMillis, author, summary, "", Markup.PLAINTEXT, changes);
    }

    /**
     * Adds the specified changes to this {@link Repository}.
     *
     * @return the {@link Revision} of the new {@link Commit}
     */
    default CompletableFuture<Revision> commit(Revision baseRevision, long commitTimeMillis,
                                               Author author, String summary, Change<?>... changes) {
        return commit(baseRevision, commitTimeMillis, author, summary, "", Markup.PLAINTEXT, changes);
    }

    /**
     * Adds the specified changes to this {@link Repository}.
     *
     * @return the {@link Revision} of the new {@link Commit}
     */
    default CompletableFuture<Revision> commit(Revision baseRevision, long commitTimeMillis,
                                               Author author, String summary, String detail, Markup markup,
                                               Change<?>... changes) {
        requireNonNull(changes, "changes");
        return commit(baseRevision, commitTimeMillis, author, summary, detail, markup, Arrays.asList(changes));
    }

    /**
     * Adds the specified changes to this {@link Repository}.
     *
     * @return the {@link Revision} of the new {@link Commit}
     */
    CompletableFuture<Revision> commit(Revision baseRevision, long commitTimeMillis,
                                       Author author, String summary, String detail, Markup markup,
                                       Iterable<Change<?>> changes);

    /**
     * Get a list of {@link Commit} for given pathPattern.
     *
     * @param from the starting revision (inclusive)
     * @param to the end revision (inclusive)
     *
     * @param pathPattern the path pattern
     * @return {@link Commit}
     *
     * @throws StorageException when any internal error occurs.
     */
    default CompletableFuture<List<Commit>> history(Revision from, Revision to, String pathPattern) {
        return history(from, to, pathPattern, DEFAULT_MAX_COMMITS);
    }

    /**
     * Get a list of {@link Commit} for given pathPattern.
     *
     * @param from the starting revision (inclusive)
     * @param to the end revision (inclusive)
     * @param maxCommits the maximum number of {@link Commit}s to return
     *
     * @param pathPattern the path pattern
     * @return {@link Commit}
     *
     * @throws StorageException when any internal error occurs.
     */
    CompletableFuture<List<Commit>> history(Revision from, Revision to, String pathPattern, int maxCommits);

    /**
     * Awaits and retrieves the latest revision of the commit that changed the file that matches the specified
     * {@code pathPattern} since the specified last known revision.
     */
    CompletableFuture<Revision> watch(Revision lastKnownRevision, String pathPattern);

    /**
     * Awaits and retrieves the change in the query result of the specified file asynchronously since the
     * specified last known revision.
     */
    default <T> CompletableFuture<QueryResult<T>> watch(Revision lastKnownRevision, Query<T> query) {
        return RepositoryUtil.watch(this, lastKnownRevision, query);
    }
}
