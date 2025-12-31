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

package com.linecorp.centraldogma.common;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A query on a file.
 *
 * @param <T> the content type of a file being queried
 */
public interface Query<T> extends Function<T, T> {

    /**
     * Returns a newly-created {@link Query} that retrieves the content as it is.
     *
     * @param path the path of a file being queried on
     *
     * @deprecated Use {@link #ofText(String)} or {@link #ofJson(String)}.
     */
    @Deprecated
    static Query<Object> identity(String path) {
        return new IdentityQuery<>(path, QueryType.IDENTITY);
    }

    /**
     * Returns a newly-created {@link Query} that retrieves the textual content as it is.
     *
     * @param path the path of a file being queried on
     */
    static Query<String> ofText(String path) {
        return new IdentityQuery<>(path, QueryType.IDENTITY_TEXT);
    }

    /**
     * Returns a newly-created {@link Query} that retrieves the JSON content as it is.
     *
     * @param path the path of a file being queried on
     */
    static Query<JsonNode> ofJson(String path) {
        return new IdentityQuery<>(path, QueryType.IDENTITY_JSON);
    }

    /**
     * Returns a newly-created {@link Query} that retrieves the YAML content as {@link JsonNode}.
     *
     * @param path the path of a file being queried on
     */
    static Query<JsonNode> ofYaml(String path) {
        return new IdentityQuery<>(path, QueryType.IDENTITY_YAML);
    }

    /**
     * Returns a newly-created {@link Query} that applies a series of
     * <a href="https://github.com/json-path/JsonPath/blob/master/README.md">JSON path expressions</a>
     * to the content.
     *
     * @param path the path of a file being queried on
     * @param jsonPaths the JSON path expressions to apply
     */
    static Query<JsonNode> ofJsonPath(String path, String... jsonPaths) {
        return new JsonPathQuery(path, jsonPaths);
    }

    /**
     * Returns a newly-created {@link Query} that applies a series of
     * <a href="https://github.com/json-path/JsonPath/blob/master/README.md">JSON path expressions</a>
     * to the content.
     *
     * @param path the path of a file being queried on
     * @param jsonPaths the JSON path expressions to apply
     */
    static Query<JsonNode> ofJsonPath(String path, Iterable<String> jsonPaths) {
        return new JsonPathQuery(path, jsonPaths);
    }

    /**
     * Returns a newly-created {@link Query} that applies a series of expressions to the content.
     *
     * @param type the type of the {@link Query}
     * @param path the path of a file being queried on
     * @param expressions the expressions to apply
     */
    static Query<?> of(QueryType type, String path, @Nullable String... expressions) {
        requireNonNull(type, "type");
        switch (type) {
            case IDENTITY:
                return new IdentityQuery<>(path, QueryType.IDENTITY);
            case IDENTITY_TEXT:
                return new IdentityQuery<>(path, QueryType.IDENTITY_TEXT);
            case IDENTITY_JSON:
                return new IdentityQuery<>(path, QueryType.IDENTITY_JSON);
            case IDENTITY_YAML:
                return new IdentityQuery<>(path, QueryType.IDENTITY_YAML);
            case JSON_PATH:
                requireNonNull(expressions, "expressions");
                return ofJsonPath(path, expressions);
            default:
                throw new IllegalArgumentException("Illegal query type: " + type.name());
        }
    }

    /**
     * Returns the path of the file being queried on.
     */
    String path();

    /**
     * Returns the type of this {@link Query}.
     */
    QueryType type();

    /**
     * Returns the list of the query expressions of this {@link Query}.
     */
    List<String> expressions();
}
