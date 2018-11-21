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
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.centraldogma.internal.Util;

final class JsonMergeQuery implements MergeQuery<JsonNode> {

    private final QueryType type;

    private final List<MergeSource> mergeSources;

    private final List<String> jsonPaths;

    JsonMergeQuery(QueryType type, Iterable<MergeSource> mergeSources, Iterable<String> jsonPaths) {
        this.type = requireNonNull(type, "type");
        this.mergeSources = ImmutableList.copyOf(requireNonNull(mergeSources, "mergeSources"));
        Streams.stream(requireNonNull(jsonPaths, "jsonPaths"))
               .forEach(jsonPath -> Util.validateJsonPath(jsonPath, "jsonPath"));
        this.jsonPaths = ImmutableList.copyOf(jsonPaths);
    }

    @Override
    public QueryType type() {
        return type;
    }

    @Override
    public List<MergeSource> mergeSources() {
        return mergeSources;
    }

    @Override
    public List<String> expressions() {
        return jsonPaths;
    }

    @Override
    public int hashCode() {
        return (type.hashCode() * 31 + mergeSources.hashCode()) * 31 + jsonPaths.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JsonMergeQuery)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        final JsonMergeQuery that = (JsonMergeQuery) o;
        return type() == that.type() && mergeSources().equals(that.mergeSources()) &&
               expressions().equals(that.expressions());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("type", type())
                          .add("mergeSources", mergeSources())
                          .add("expressions", expressions())
                          .toString();
    }
}
