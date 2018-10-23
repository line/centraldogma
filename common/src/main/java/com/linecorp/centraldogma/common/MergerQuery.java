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

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

/**
 * A merger query on files.
 *
 * @param <T> the content type of files being queried
 */
public interface MergerQuery<T> {

    /**
     * Returns a newly-created {@link MergerQuery} that merges the JSON contents in the specified
     * {@code pathAndOptionals}.
     *
     * @param pathAndOptionals the paths of JSON files being merged and indicates whether it is optional
     */
    static MergerQuery<JsonNode> ofJson(PathAndOptional... pathAndOptionals) {
        return ofJsonPath(ImmutableList.copyOf(requireNonNull(pathAndOptionals, "pathAndOptionals")));
    }

    /**
     * Returns a newly-created {@link MergerQuery} that merges the JSON contents in the specified
     * {@code pathAndOptionals}.
     *
     * @param pathAndOptionals the paths of JSON files being merged and indicates whether it is optional
     */
    static MergerQuery<JsonNode> ofJson(Iterable<PathAndOptional> pathAndOptionals) {
        return ofJsonPath(pathAndOptionals);
    }

    /**
     * Returns a newly-created {@link MergerQuery} that merges the JSON contents in the specified
     * {@code pathAndOptionals}. Then, the specified
     * <a href="https://github.com/json-path/JsonPath/blob/master/README.md">JSON path expressions</a>
     * are applied to the content.
     *
     * @param pathAndOptionals the paths of JSON files being merged and indicates whether it is optional
     * @param jsonPaths the JSON path expressions to apply
     */
    static MergerQuery<JsonNode> ofJsonPath(Iterable<PathAndOptional> pathAndOptionals,
                                            String... jsonPaths) {
        return ofJsonPath(pathAndOptionals,
                          ImmutableList.copyOf(requireNonNull(jsonPaths, "jsonPaths")));
    }

    /**
     * Returns a newly-created {@link MergerQuery} that merges the JSON contents in the specified
     * {@code pathAndOptionals}. Then, the specified
     * <a href="https://github.com/json-path/JsonPath/blob/master/README.md">JSON path expressions</a>
     * are applied to the content.
     *
     * @param pathAndOptionals the paths of JSON files being merged and indicates whether it is optional
     * @param jsonPaths the JSON path expressions to apply
     */
    static MergerQuery<JsonNode> ofJsonPath(Iterable<PathAndOptional> pathAndOptionals,
                                            Iterable<String> jsonPaths) {
        return new JsonMergerQuery(pathAndOptionals, jsonPaths);
    }

    /**
     * Returns the type of this {@link MergerQuery}.
     */
    QueryType type();

    /**
     * Returns the list of {@link PathAndOptional} which will be merged into one entry.
     */
    List<PathAndOptional> pathAndOptionals();

    /**
     * Returns the list of the query expressions of this {@link MergerQuery}.
     */
    List<String> expressions();
}
