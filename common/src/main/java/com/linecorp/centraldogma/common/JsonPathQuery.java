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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.internal.Util.validateJsonFilePath;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Streams;
import com.jayway.jsonpath.JsonPath;

import com.linecorp.centraldogma.internal.Jackson;

final class JsonPathQuery implements Query<JsonNode> {

    private final String path;
    private final List<String> jsonPaths;
    private int hashCode;
    private String strVal;

    JsonPathQuery(String path, String... jsonPaths) {
        requireNonNull(jsonPaths, "jsonPaths");

        this.path = validateJsonFilePath(path, "path");
        this.jsonPaths = Stream.of(jsonPaths).peek(JsonPathQuery::validateJsonPath).collect(toImmutableList());
    }

    @JsonCreator
    JsonPathQuery(@JsonProperty("path") String path,
                  @JsonProperty("expressions") Iterable<String> jsonPaths) {
        requireNonNull(jsonPaths, "jsonPaths");

        this.path = validateJsonFilePath(path, "path");
        this.jsonPaths = Streams.stream(jsonPaths)
                                .peek(JsonPathQuery::validateJsonPath)
                                .collect(toImmutableList());
    }

    private static void validateJsonPath(String expr) {
        try {
            JsonPath.compile(expr);
        } catch (Exception e) {
            throw new QueryException("expression syntax error: " + expr, e);
        }
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public QueryType type() {
        return QueryType.JSON_PATH;
    }

    @Override
    @JsonProperty
    public List<String> expressions() {
        return jsonPaths;
    }

    @Override
    public int hashCode() {
        if (hashCode != 0) {
            return hashCode;
        }

        return hashCode = path.hashCode() * 31 + jsonPaths.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof JsonPathQuery)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        final JsonPathQuery that = (JsonPathQuery) obj;

        return hashCode() == that.hashCode() &&
               path().equals(that.path()) && expressions().equals(that.expressions());
    }

    @Override
    public JsonNode apply(JsonNode input) {
        requireNonNull(input, "input");

        final int size = jsonPaths.size();
        for (int i = 0; i < size; i++) {
            final String p = jsonPaths.get(i);
            input = Jackson.extractTree(input, p);
        }

        return input;
    }

    @Override
    public String toString() {
        String strVal = this.strVal;
        if (strVal == null) {
            final StringBuilder buf = new StringBuilder(path.length() + jsonPaths.size() * 16 + 16);
            buf.append("JsonPathQuery(");
            buf.append(path);
            for (int i = 0; i < jsonPaths.size(); i++) {
                buf.append(", ");
                buf.append(jsonPaths.get(i));
            }
            buf.append(')');

            this.strVal = strVal = buf.toString();
        }

        return strVal;
    }
}
