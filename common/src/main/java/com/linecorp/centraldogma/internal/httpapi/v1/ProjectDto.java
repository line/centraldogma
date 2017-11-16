/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.internal.httpapi.v1;

import static com.linecorp.centraldogma.internal.httpapi.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.httpapi.v1.HttpApiV1Constants.REPOS;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Author;

@JsonInclude(Include.NON_NULL)
public class ProjectDto {

    private final String name;

    private Author creator;

    private String url;

    private String reposUrl;

    private String createdAt;

    @JsonCreator
    public ProjectDto(String name) {
        this.name = requireNonNull(name, "name");
    }

    @JsonCreator
    public ProjectDto(String name, Author creator, long creationTimeMillis) {
        this.name = requireNonNull(name, "name");
        this.creator = requireNonNull(creator, "creator");
        this.createdAt = ISO_INSTANT.format(Instant.ofEpochMilli(creationTimeMillis));
        url = PROJECTS_PREFIX + '/' + name;
        reposUrl = url + REPOS;
    }

    @JsonProperty("name")
    public String name() {
        return name;
    }

    @JsonProperty("creator")
    public Author creator() {
        return creator;
    }

    @JsonProperty("url")
    public String url() {
        return url;
    }

    @JsonProperty("reposUrl")
    public String reposUrl() {
        return reposUrl;
    }

    @JsonProperty("createdAt")
    public String createdAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name())
                          .add("creator", creator())
                          .add("createdAt", createdAt())
                          .toString();
    }
}

