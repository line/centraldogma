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

package com.linecorp.centraldogma.internal.api.v1;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.CONTENTS;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Revision;

@JsonInclude(Include.NON_NULL)
public class EntryDto<T> {

    private final Revision revision;
    @Nullable
    private final Revision variableRevision;

    private final String path;

    /**
     * The type of the entry.
     *
     * @deprecated Clients should use {@link EntryType#guessFromPath(String)} instead. {@link EntryType} makes
     *             it difficult to support new types.
     */
    @Deprecated
    private final EntryType type;

    @Nullable
    private final T content;
    @Nullable
    private final String rawContent;

    private final String url;

    public EntryDto(Revision revision, String path, EntryType type,
                    String projectName, String repoName, @Nullable T content, @Nullable String rawContent,
                    @Nullable Revision variableRevision) {
        this.revision = requireNonNull(revision, "revision");
        this.path = requireNonNull(path, "path");
        this.type = requireNonNull(type, "type");
        this.content = content;
        this.rawContent = rawContent;
        this.variableRevision = variableRevision;
        url = PROJECTS_PREFIX + '/' + projectName + REPOS + '/' + repoName + CONTENTS + path;
    }

    @JsonProperty
    public Revision revision() {
        return revision;
    }

    @Nullable
    @JsonProperty
    public Revision variableRevision() {
        return variableRevision;
    }

    @JsonProperty
    public String path() {
        return path;
    }

    @Deprecated
    @JsonProperty
    public EntryType type() {
        return type;
    }

    @JsonProperty
    @Nullable
    public T content() {
        return content;
    }

    @JsonProperty
    @Nullable
    public String rawContent() {
        return rawContent;
    }

    @JsonProperty
    @Nullable
    public String url() {
        return url;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("revision", revision)
                          .add("variableRevision", variableRevision)
                          .add("path", path)
                          .add("type", type)
                          .add("content", content).toString();
    }
}
