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

package com.linecorp.centraldogma.common;

import static com.linecorp.centraldogma.common.QueryType.IDENTITY;
import static com.linecorp.centraldogma.common.QueryType.JSON_PATH;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

/**
 * A merge query on files.
 *
 * @param <T> the content type of files being queried
 */
public interface MergeQuery<T> {

    /**
     * Returns a newly-created {@link MergeQuery} that merges the JSON contents as specified in the
     * {@code mergeSources}.
     *
     * @param mergeSources the paths of JSON files being merged and indicates whether it is optional
     */
    static MergeQuery<JsonNode> ofJson(MergeSource... mergeSources) {
        return ofJson(ImmutableList.copyOf(requireNonNull(mergeSources, "mergeSources")));
    }

    /**
     * Returns a newly-created {@link MergeQuery} that merges the JSON contents as specified in the
     * {@code mergeSources}.
     *
     * @param mergeSources the paths of JSON files being merged and indicates whether it is optional
     */
    static MergeQuery<JsonNode> ofJson(Iterable<MergeSource> mergeSources) {
        return new JsonMergeQuery(IDENTITY, mergeSources, ImmutableList.of());
    }

    /**
     * Returns a newly-created {@link MergeQuery} that merges the JSON contents as specified in the
     * {@code mergeSources}. Then, the specified
     * <a href="https://github.com/json-path/JsonPath/blob/master/README.md">JSON path expressions</a>
     * are applied to the content of the {@link MergedEntry}.
     *
     * @param mergeSources the paths of JSON files being merged and indicates whether it is optional
     * @param jsonPaths the JSON path expressions to apply
     */
    static MergeQuery<JsonNode> ofJsonPath(Iterable<MergeSource> mergeSources,
                                           String... jsonPaths) {
        return ofJsonPath(mergeSources, ImmutableList.copyOf(requireNonNull(jsonPaths, "jsonPaths")));
    }

    /**
     * Returns a newly-created {@link MergeQuery} that merges the JSON contents as specified in the
     * {@code mergeSources}. Then, the specified
     * <a href="https://github.com/json-path/JsonPath/blob/master/README.md">JSON path expressions</a>
     * are applied to the content.
     *
     * @param mergeSources the paths of JSON files being merged and indicates whether it is optional
     * @param jsonPaths the JSON path expressions to apply
     */
    static MergeQuery<JsonNode> ofJsonPath(Iterable<MergeSource> mergeSources,
                                           Iterable<String> jsonPaths) {
        if (Iterables.isEmpty(jsonPaths)) {
            return ofJson(mergeSources);
        }
        return new JsonMergeQuery(JSON_PATH, mergeSources, jsonPaths);
    }

    /**
     * Returns a newly-created {@link MergeQuery} that merges the JSON contents as specified in the
     * {@code mergeSources}. Then, the specified expressions are applied to the content of the
     * {@link MergedEntry}.
     *
     * @param type the type of the {@link MergeQuery}
     * @param mergeSources the paths of JSON files being merged and indicates whether it is optional
     * @param expressions the expressions to apply to the content of the {@link MergedEntry}
     */
    static MergeQuery<?> of(QueryType type, Iterable<MergeSource> mergeSources,
                            Iterable<String> expressions) {
        requireNonNull(type, "type");
        switch (type) {
            case IDENTITY:
                return ofJson(mergeSources);
            case JSON_PATH:
                return ofJsonPath(mergeSources, expressions);
            default:
                throw new IllegalArgumentException("Illegal query type: " + type.name());
        }
    }

    /**
     * Returns the type of this {@link MergeQuery}.
     */
    QueryType type();

    /**
     * Returns the list of {@link MergeSource}s which will be merged into one entry.
     */
    List<MergeSource> mergeSources();

    /**
     * Returns the list of the query expressions of this {@link MergeQuery}.
     */
    List<String> expressions();
}
