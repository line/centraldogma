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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A query on a file.
 *
 * @param <T> the content type of a file being queried
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @Type(value = IdentityQuery.class, name = "IDENTITY"),
        @Type(value = JsonPathQuery.class, name = "JSON_PATH"),
})
public interface Query<T> extends Function<T, T> {

    /**
     * Returns a newly-created {@link Query} that retrieves the content as it is.
     *
     * @param path the path of a file being queried on
     */
    static Query<Object> identity(String path) {
        return new IdentityQuery(path);
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
                return identity(path);
            case JSON_PATH:
                return ofJsonPath(path, expressions);
            default:
                throw new IllegalArgumentException("Illegal query type: " + type.name());
        }
    }

    /**
     * Returns the path of the file being queried on.
     */
    @JsonProperty
    String path();

    /**
     * Returns the type of this {@link Query}.
     */
    @JsonProperty
    QueryType type();

    /**
     * Returns the list of the query expressions of this {@link Query}.
     */
    List<String> expressions();
}
