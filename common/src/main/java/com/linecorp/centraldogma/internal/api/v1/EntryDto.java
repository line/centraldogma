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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.NullNode;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.EntryType;

@JsonInclude(Include.NON_NULL)
public class EntryDto<T> {

    private final String path;

    private final EntryType type;

    private final T content;

    private String url;

    public EntryDto(String path, EntryType type, String projectName, String repoName, @Nullable T content) {
        this.path = requireNonNull(path, "path");
        this.type = requireNonNull(type, "type");
        if (content instanceof NullNode || (content instanceof String && isNullOrEmpty((String) content))) {
            this.content = null;
        } else {
            this.content = content;
        }
        url = PROJECTS_PREFIX + '/' + projectName + REPOS + '/' + repoName + path;
    }

    @JsonProperty("path")
    public String path() {
        return path;
    }

    @JsonProperty("type")
    public EntryType type() {
        return type;
    }

    @JsonProperty("content")
    @Nullable
    public T content() {
        return content;
    }

    @JsonProperty("url")
    @Nullable
    public String url() {
        return url;
    }

    @Override
    public String toString() {
        final ToStringHelper stringHelper = MoreObjects.toStringHelper(this)
                                                       .add("path", path())
                                                       .add("type", type());
        if (content() != null) {
            stringHelper.add("content", content());
        }

        return stringHelper.toString();
    }
}
