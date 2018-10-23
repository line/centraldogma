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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.centraldogma.internal.Util;

final class JsonMergerQuery implements MergerQuery<JsonNode> {

    private final List<PathAndOptional> pathAndOptionals;

    private final List<String> jsonPaths;

    JsonMergerQuery(Iterable<PathAndOptional> pathAndOptionals, Iterable<String> jsonPaths) {
        this.pathAndOptionals = ImmutableList.copyOf(requireNonNull(pathAndOptionals, "pathAndOptionals"));
        this.jsonPaths = Streams.stream(requireNonNull(jsonPaths, "jsonPaths"))
                                .peek(jsonPath -> Util.validateJsonPath(jsonPath, "jsonPath"))
                                .collect(toImmutableList());
    }

    @Override
    public QueryType type() {
        return QueryType.JSON_MERGER;
    }

    @Override
    public List<PathAndOptional> pathAndOptionals() {
        return pathAndOptionals;
    }

    @Override
    public List<String> expressions() {
        return jsonPaths;
    }

    @Override
    public int hashCode() {
        return pathAndOptionals.hashCode() * 31 + jsonPaths.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JsonMergerQuery)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        final JsonMergerQuery that = (JsonMergerQuery) o;
        return pathAndOptionals().equals(that.pathAndOptionals()) && expressions().equals(that.expressions());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("queryType", QueryType.JSON_MERGER)
                          .add("pathAndOptionals", pathAndOptionals())
                          .add("expressions", expressions())
                          .toString();
    }
}
