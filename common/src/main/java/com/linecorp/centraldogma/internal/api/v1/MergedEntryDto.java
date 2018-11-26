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

package com.linecorp.centraldogma.internal.api.v1;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Revision;

public class MergedEntryDto<T> {

    private final Revision revision;

    private final EntryType type;

    private final T content;

    private List<String> paths;

    public MergedEntryDto(Revision revision, EntryType type, T content, Iterable<String> paths) {
        this.revision = requireNonNull(revision, "revision");
        this.type = requireNonNull(type, "type");
        this.content = requireNonNull(content, "content");
        this.paths = ImmutableList.copyOf(requireNonNull(paths, "paths"));
    }

    @JsonProperty("revision")
    public Revision revision() {
        return revision;
    }

    @JsonProperty("type")
    public EntryType type() {
        return type;
    }

    @JsonProperty("content")
    public T content() {
        return content;
    }

    @JsonProperty("paths")
    public List<String> paths() {
        return paths;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("revision", revision)
                          .add("type", type)
                          .add("content", content)
                          .add("paths", paths)
                          .toString();
    }
}
