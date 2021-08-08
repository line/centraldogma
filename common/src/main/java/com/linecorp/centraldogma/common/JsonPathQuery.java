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

import static com.linecorp.centraldogma.internal.Util.validateJsonOrYamlFilePath;
import static java.util.Objects.requireNonNull;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.jackson.Jackson;

final class JsonPathQuery implements Query<JsonNode> {

    private final String path;
    private final List<String> jsonPaths;
    private int hashCode;
    @Nullable
    private String strVal;

    JsonPathQuery(String path, String... jsonPaths) {
        this(path, ImmutableList.copyOf(requireNonNull(jsonPaths, "jsonPaths")));
    }

    JsonPathQuery(String path, Iterable<String> jsonPaths) {
        this.path = validateJsonOrYamlFilePath(path, "path");
        Streams.stream(requireNonNull(jsonPaths, "jsonPaths"))
               .forEach(jsonPath -> Util.validateJsonPath(jsonPath, "jsonPath"));
        this.jsonPaths = ImmutableList.copyOf(jsonPaths);
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
        return Jackson.ofJson().extractTree(input, jsonPaths);
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
