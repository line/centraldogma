/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.centraldogma.server.internal;

import static com.linecorp.centraldogma.internal.Util.validateJsonFilePath;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;
import com.spotify.futures.CompletableFutures;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.MergerQuery;
import com.linecorp.centraldogma.common.PathAndOptional;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.MergedEntryDto;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * Utility methods that are useful when implementing a service.
 */
public final class ServiceUtil {

    /**
     * Merges the JSON files in the specified {@link MergerQuery}.
     */
    public static <T> CompletableFuture<MergedEntryDto<JsonNode>> mergeFiles(
            Revision normalizedRevision, Repository repository, MergerQuery<T> query) {

        requireNonNull(normalizedRevision, "normalizedRevision");
        requireNonNull(repository, "repository");
        requireNonNull(query, "query");

        final List<PathAndOptional> paths = query.pathAndOptionals();
        // Only JSON files can currently be merged.
        paths.forEach(path -> validateJsonFilePath(path.getPath(), "path"));

        final List<CompletableFuture<Entry<?>>> entryFutures = new ArrayList<>(Iterables.size(paths));
        paths.forEach(path -> {
            if (!path.isOptional()) {
                entryFutures.add(repository.get(normalizedRevision, path.getPath()));
            } else {
                entryFutures.add(repository.getOrNull(normalizedRevision, path.getPath()));
            }
        });

        final CompletableFuture<MergedEntryDto<JsonNode>> future = new CompletableFuture<>();
        CompletableFutures.allAsList(entryFutures).whenComplete((entries, cause) -> {
            if (cause != null) {
                future.completeExceptionally(cause);
            }

            final Builder<JsonNode> builder = ImmutableList.builder();
            for (Entry<?> entry : entries) {
                if (entry == null) {
                    continue;
                }
                try {
                    builder.add(entry.contentAsJson());
                } catch (JsonParseException e) {
                    future.completeExceptionally(e);
                    return;
                }
            }

            JsonNode result = Jackson.mergeJsonNodes(builder.build());
            final List<String> expressions = query.expressions();
            if (!expressions.isEmpty()) {
                try {
                    result = Jackson.extractTree(result, expressions);
                } catch (Exception e) {
                    // A user sent the request with a invalid JSON path or pointing to the property which
                    // the merged JSON doesn't have.
                    future.completeExceptionally(new IllegalArgumentException(e));
                    return;
                }
            }

            future.complete(new MergedEntryDto<>(EntryType.JSON, result));
        });
        return future;
    }

    private ServiceUtil() {}
}
