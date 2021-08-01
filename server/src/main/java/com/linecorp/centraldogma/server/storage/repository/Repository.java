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

package com.linecorp.centraldogma.server.storage.repository;

import static com.linecorp.centraldogma.internal.Util.unsafeCast;
import static com.linecorp.centraldogma.internal.Util.validateFilePath;
import static com.linecorp.centraldogma.internal.Util.validateJsonOrYamlFilePath;
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ONE_WITHOUT_CONTENT;
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ONE_WITH_CONTENT;
import static com.linecorp.centraldogma.server.storage.repository.RepositoryUtil.applyQuery;
import static com.linecorp.centraldogma.server.storage.repository.RepositoryUtil.mergeEntries;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.spotify.futures.CompletableFutures;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryExecutionException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.internal.replication.ReplicationLog;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.project.Project;

/**
 * Revision-controlled filesystem-like repository.
 */
public interface Repository {

    int DEFAULT_MAX_COMMITS = 100;
    int MAX_MAX_COMMITS = 1000;

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
     * @deprecated Use {@link #normalizeNow(Revision)}.
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
        return find(revision, path, FIND_ONE_WITHOUT_CONTENT).thenApply(result -> !result.isEmpty());
    }

    /**
     * Retrieves an {@link Entry} at the specified {@code path}.
     *
     * @throws EntryNotFoundException if there's no entry at the specified {@code path}
     *
     * @see #getOrNull(Revision, String)
     */
    default CompletableFuture<Entry<?>> get(Revision revision, String path) {
        return getOrNull(revision, path).thenApply(entry -> {
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
     * @see #getOrNull(Revision, Query)
     */
    default <T> CompletableFuture<Entry<T>> get(Revision revision, Query<T> query) {
        return getOrNull(revision, query).thenApply(res -> {
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
    default CompletableFuture<Entry<?>> getOrNull(Revision revision, String path) {
        validateFilePath(path, "path");

        return find(revision, path, FIND_ONE_WITH_CONTENT).thenApply(findResult -> findResult.get(path));
    }

    /**
     * Performs the specified {@link Query}.
     *
     * @return the {@link Entry} on a successful query.
     *         The specified {@code other} on a failure due to missing entry.
     *
     * @see #get(Revision, Query)
     */
    default <T> CompletableFuture<Entry<T>> getOrNull(Revision revision, Query<T> query) {
        requireNonNull(query, "query");
        requireNonNull(revision, "revision");

        return getOrNull(revision, query.path()).thenApply(result -> {
            if (result == null) {
                return null;
            }

            @SuppressWarnings("unchecked")
            final Entry<T> entry = (Entry<T>) result;

            try {
                return applyQuery(entry, query);
            } catch (CentralDogmaException e) {
                throw e;
            } catch (Exception e) {
                throw new QueryExecutionException(e);
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

        final RevisionRange range;
        try {
            range = normalizeNow(from, to).toAscending();
        } catch (Exception e) {
            return CompletableFutures.exceptionallyCompletedFuture(e);
        }

        final String path = query.path();
        final CompletableFuture<Entry<?>> fromEntryFuture = getOrNull(range.from(), path);
        final CompletableFuture<Entry<?>> toEntryFuture = getOrNull(range.to(), path);

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
                            throw new QueryExecutionException("unsupported entry type: " + toEntryType);
                        }

                        final Object toContent = castQuery.apply(toEntry.content());

                        switch (toEntryType) {
                            case JSON:
                                return Change.ofJsonUpsert(path, (JsonNode) toContent);
                            case YAML:
                                return Change.ofYamlUpsert(path, (JsonNode) toContent);
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
                        throw new QueryExecutionException("unsupported entry type: " + entryType);
                    }
                    if (entryType != toEntry.type()) {
                        throw new QueryExecutionException(
                                "mismatching entry type: " + entryType + " != " + toEntry.type());
                    }

                    final Object fromContent = castQuery.apply(fromEntry.content());
                    final Object toContent = castQuery.apply(toEntry.content());

                    switch (entryType) {
                        case JSON:
                            return Change.ofJsonPatch(path, (JsonNode) fromContent, (JsonNode) toContent);
                        case YAML:
                            return Change.ofYamlPatch(path, (JsonNode) fromContent, (JsonNode) toContent);
                        case TEXT:
                            return Change.ofTextPatch(path, (String) fromContent, (String) toContent);
                        default:
                            throw new Error();
                    }
                }).toCompletableFuture();

        return unsafeCast(future);
    }

    /**
     * Returns the diff for all files that are matched by the specified {@code pathPattern}
     * between the specified two {@link Revision}s.
     */
    CompletableFuture<Map<String, Change<?>>> diff(Revision from, Revision to, String pathPattern);

    /**
     * Generates the preview diff against the specified {@code baseRevision} and {@code changes}.
     */
    default CompletableFuture<Map<String, Change<?>>> previewDiff(Revision baseRevision, Change<?>... changes) {
        requireNonNull(changes, "changes");
        return previewDiff(baseRevision, Arrays.asList(changes));
    }

    /**
     * Generates the preview diff against the specified {@code baseRevision} and {@code changes}.
     */
    CompletableFuture<Map<String, Change<?>>> previewDiff(Revision baseRevision, Iterable<Change<?>> changes);

    /**
     * Adds the specified changes to this {@link Repository}.
     *
     * @return the {@link Revision} of the new {@link Commit}
     */
    default CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis,
                                                   Author author, String summary, Iterable<Change<?>> changes) {
        return commit(baseRevision, commitTimeMillis, author, summary, "", Markup.PLAINTEXT, changes,
                      true);
    }

    /**
     * Adds the specified changes to this {@link Repository}.
     *
     * @return the {@link Revision} of the new {@link Commit}
     */
    default CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis,
                                                   Author author, String summary, Change<?>... changes) {
        return commit(baseRevision, commitTimeMillis, author, summary, "", Markup.PLAINTEXT, changes);
    }

    /**
     * Adds the specified changes to this {@link Repository}.
     *
     * @return the {@link Revision} of the new {@link Commit}
     */
    default CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis,
                                                   Author author, String summary, String detail, Markup markup,
                                                   Change<?>... changes) {
        requireNonNull(changes, "changes");
        return commit(baseRevision, commitTimeMillis, author, summary, detail, markup,
                      ImmutableList.copyOf(changes), true);
    }

    /**
     * Adds the specified changes to this {@link Repository}.
     *
     * @param baseRevision the base {@link Revision} of this {@link Commit}
     * @param commitTimeMillis the time and date of this {@link Commit}, represented as the number of
     *                         milliseconds since the epoch (midnight, January 1, 1970 UTC)
     * @param author the {@link Author} of this {@link Commit}
     * @param summary the human-readable summary of this {@link Commit}
     * @param detail the human-readable detailed description of this {@link Commit}
     * @param markup the {@link Markup} language of {@code summary} and {@code detail}
     * @param changes the changes to be applied
     * @param directExecution whether this {@link Commit} is received by this server and executed directly.
     *                        {@code false} if this commit is delivered by a {@link ReplicationLog}.
     *
     * @return the {@link Revision} of the new {@link Commit}
     */
    CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis,
                                           Author author, String summary, String detail, Markup markup,
                                           Iterable<Change<?>> changes, boolean directExecution);

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
     * Returns the latest {@link Revision} if there are any {@link Change}s since {@code lastKnownRevision}
     * that affected the path matched by the specified {@code pathPattern}. The behavior of this method could
     * be represented as the following code:
     * <pre>{@code
     * RevisionRange range = repository.normalizeNow(lastKnownRevision, Revision.HEAD);
     * return repository.diff(range.from(), range.to(), pathPattern).thenApply(diff -> {
     *     if (diff.isEmpty()) {
     *         return null;
     *     } else {
     *         return range.to();
     *     }
     * });
     * }</pre>
     * .. although it would be implemented more efficiently.
     *
     * @return the latest {@link Revision} if there's a match, or {@code null} if there's no match or
     *         {@code lastKnownRevision} is the latest {@link Revision}
     */
    default CompletableFuture<Revision> findLatestRevision(Revision lastKnownRevision, String pathPattern) {
        return findLatestRevision(lastKnownRevision, pathPattern, false);
    }

    /**
     * Returns the latest {@link Revision} if there are any {@link Change}s since {@code lastKnownRevision}
     * that affected the path matched by the specified {@code pathPattern}. The behavior of this method could
     * be represented as the following code:
     * <pre>{@code
     * RevisionRange range = repository.normalizeNow(lastKnownRevision, Revision.HEAD);
     * return repository.diff(range.from(), range.to(), pathPattern).thenApply(diff -> {
     *     if (diff.isEmpty()) {
     *         return null;
     *     } else {
     *         return range.to();
     *     }
     * });
     * }</pre>
     * .. although it would be implemented more efficiently.
     *
     * @return the latest {@link Revision} if there's a match, or {@code null} if there's no match or
     *         {@code lastKnownRevision} is the latest {@link Revision}
     */
    CompletableFuture<Revision> findLatestRevision(Revision lastKnownRevision, String pathPattern,
                                                   boolean errorOnEntryNotFound);

    /**
     * Awaits and retrieves the latest revision of the commit that changed the file that matches the specified
     * {@code pathPattern} since the specified last known revision.
     */
    default CompletableFuture<Revision> watch(Revision lastKnownRevision, String pathPattern) {
        return watch(lastKnownRevision, pathPattern, false);
    }

    /**
     * Awaits and retrieves the latest revision of the commit that changed the file that matches the specified
     * {@code pathPattern} since the specified last known revision.
     */
    CompletableFuture<Revision> watch(Revision lastKnownRevision, String pathPattern,
                                      boolean errorOnEntryNotFound);

    /**
     * Awaits and retrieves the change in the query result of the specified file asynchronously since the
     * specified last known revision.
     */
    default <T> CompletableFuture<Entry<T>> watch(Revision lastKnownRevision, Query<T> query) {
        return watch(lastKnownRevision, query, false);
    }

    /**
     * Awaits and retrieves the change in the query result of the specified file asynchronously since the
     * specified last known revision.
     */
    default <T> CompletableFuture<Entry<T>> watch(Revision lastKnownRevision, Query<T> query,
                                                  boolean errorOnEntryNotFound) {
        return RepositoryUtil.watch(this, lastKnownRevision, query, errorOnEntryNotFound);
    }

    /**
     * Merges the JSON files sequentially as specified in the {@link MergeQuery}.
     */
    default <T> CompletableFuture<MergedEntry<T>> mergeFiles(Revision revision, MergeQuery<T> query) {
        requireNonNull(revision, "revision");
        requireNonNull(query, "query");

        final List<MergeSource> mergeSources = query.mergeSources();
        // Only JSON files can currently be merged.
        mergeSources.forEach(path -> validateJsonOrYamlFilePath(path.path(), "path"));

        final Revision normalizedRevision;
        try {
            normalizedRevision = normalizeNow(revision);
        } catch (Exception e) {
            return CompletableFutures.exceptionallyCompletedFuture(e);
        }
        final List<CompletableFuture<Entry<?>>> entryFutures = new ArrayList<>(mergeSources.size());
        mergeSources.forEach(path -> {
            if (!path.isOptional()) {
                entryFutures.add(get(normalizedRevision, path.path()));
            } else {
                entryFutures.add(getOrNull(normalizedRevision, path.path()));
            }
        });

        final CompletableFuture<MergedEntry<?>> mergedEntryFuture = mergeEntries(entryFutures, revision,
                                                                                 query);
        final CompletableFuture<MergedEntry<T>> future = new CompletableFuture<>();
        mergedEntryFuture.handle((mergedEntry, cause) -> {
            if (cause != null) {
                if (!(cause instanceof CentralDogmaException)) {
                    cause = new QueryExecutionException(cause);
                }
                future.completeExceptionally(cause);
                return null;
            }
            future.complete(unsafeCast(mergedEntry));
            return null;
        });

        return future;
    }
}
