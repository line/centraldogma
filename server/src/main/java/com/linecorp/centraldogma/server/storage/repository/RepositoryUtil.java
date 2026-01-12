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

import static com.linecorp.centraldogma.common.QueryType.IDENTITY;
import static com.linecorp.centraldogma.common.QueryType.IDENTITY_JSON;
import static com.linecorp.centraldogma.common.QueryType.IDENTITY_TEXT;
import static com.linecorp.centraldogma.common.QueryType.IDENTITY_YAML;
import static com.linecorp.centraldogma.common.QueryType.JSON_PATH;
import static com.linecorp.centraldogma.internal.Util.unsafeCast;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryExecutionException;
import com.linecorp.centraldogma.common.QuerySyntaxException;
import com.linecorp.centraldogma.common.QueryType;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;

/**
 * Utility methods that are useful when implementing a {@link Repository} implementation.
 */
final class RepositoryUtil {

    static CompletableFuture<MergedEntry<?>> mergeEntries(
            List<CompletableFuture<Entry<?>>> entryFutures, Revision revision,
            MergeQuery<?> query) {
        requireNonNull(entryFutures, "entryFutures");
        requireNonNull(revision, "revision");
        requireNonNull(query, "query");

        final CompletableFuture<MergedEntry<JsonNode>> future = new CompletableFuture<>();
        CompletableFutures.allAsList(entryFutures).handle((entries, cause) -> {
            if (cause != null) {
                future.completeExceptionally(Exceptions.peel(cause));
                return null;
            }

            final Builder<JsonNode> jsonNodesBuilder = ImmutableList.builder();
            final Builder<String> pathsBuilder = ImmutableList.builder();
            for (Entry<?> entry : entries) {
                if (entry == null) {
                    continue;
                }
                try {
                    jsonNodesBuilder.add(entry.contentAsJson());
                    pathsBuilder.add(entry.path());
                } catch (JsonParseException e) {
                    future.completeExceptionally(e);
                    return null;
                }
            }

            JsonNode result;
            try {
                final List<JsonNode> jsonNodes = jsonNodesBuilder.build();
                if (jsonNodes.isEmpty()) {
                    throw new EntryNotFoundException(revision, concatenatePaths(query.mergeSources()));
                }

                result = Jackson.mergeTree(jsonNodes);
                final List<String> expressions = query.expressions();
                if (!Iterables.isEmpty(expressions)) {
                    result = Jackson.extractTree(result, expressions);
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
                return null;
            }

            future.complete(MergedEntry.of(revision, EntryType.JSON, result, pathsBuilder.build()));
            return null;
        });
        return unsafeCast(future);
    }

    private static String concatenatePaths(Iterable<MergeSource> mergeSources) {
        return Streams.stream(mergeSources).map(MergeSource::path).collect(Collectors.joining(","));
    }

    /**
     * Applies the specified {@link Query} to the {@link Entry#content()} of the specified {@link Entry} and
     * returns the query result.
     *
     * @throws IllegalStateException if the specified {@link Entry} is a directory
     * @throws QuerySyntaxException if the syntax of specified {@link Query} is invalid
     * @throws QueryExecutionException if an {@link Exception} is raised while applying the specified
     *                                 {@link Query} to the {@link Entry#content()}
     */
    static <T> Entry<T> applyQuery(Entry<T> entry, Query<T> query) {
        requireNonNull(query, "query");
        entry.content(); // Ensure that content is not null.
        final EntryType entryType = entry.type();

        final QueryType queryType = query.type();
        if (!queryType.supportedEntryTypes().contains(entryType)) {
            throw new QueryExecutionException("Unsupported entry type: " + entryType +
                                              " (query: " + query + ')');
        }

        if (queryType == IDENTITY || queryType == IDENTITY_TEXT || queryType == IDENTITY_JSON ||
            queryType == IDENTITY_YAML) {
            return entry;
        } else if (queryType == JSON_PATH) {
            return Entry.of(entry.revision(), query.path(), entryType, query.apply(entry.content()),
                            entry.variableRevision());
        } else {
            throw new QueryExecutionException("Unsupported entry type: " + entryType +
                                              " (query: " + query + ')');
        }
    }

    private RepositoryUtil() {}
}
