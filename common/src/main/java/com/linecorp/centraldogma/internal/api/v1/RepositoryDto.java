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

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Objects.requireNonNull;

import java.time.Instant;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;

@JsonInclude(Include.NON_NULL)
public class RepositoryDto {

    private final String name;

    private Author creator;

    private Revision headRevision;

    private String url;

    private String commitsUrl;

    private String compareUrl;

    private String contentsUrl;

    private String createdAt;

    public RepositoryDto(String name) {
        this.name = requireNonNull(name, "name");
    }

    public RepositoryDto(String projectName, String repoName, Author creator, Revision headRevision,
                         long creationTimeMillis) {
        this.name = requireNonNull(repoName, "repoName");
        this.creator = requireNonNull(creator, "creator");
        this.headRevision = requireNonNull(headRevision, "headRevision");
        url = PROJECTS_PREFIX + '/' + requireNonNull(projectName, "projectName") + REPOS + '/' + repoName;
        createdAt = ISO_INSTANT.format(Instant.ofEpochMilli(creationTimeMillis));
    }

    @JsonProperty("name")
    public String name() {
        return name;
    }

    @Nullable
    @JsonProperty("creator")
    public Author creator() {
        return creator;
    }

    @Nullable
    @JsonProperty("headRevision")
    public Revision headRevision() {
        return headRevision;
    }

    @Nullable
    @JsonProperty("url")
    public String url() {
        return url;
    }

    @Nullable
    @JsonProperty("commitsUrl")
    public String commitsUrl() {
        return commitsUrl;
    }

    @Nullable
    @JsonProperty("compareUrl")
    public String compareUrl() {
        return compareUrl;
    }

    @Nullable
    @JsonProperty("contentsUrl")
    public String contentsUrl() {
        return contentsUrl;
    }

    @Nullable
    @JsonProperty("createdAt")
    public String createdAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        final ToStringHelper stringHelper = MoreObjects.toStringHelper(this)
                                                       .add("name", name());
        if (creator() != null) {
            stringHelper.add("creator", creator());
        }

        if (headRevision() != null) {
            stringHelper.add("headRevision", headRevision());
        }

        if (createdAt() != null) {
            stringHelper.add("createdAt", createdAt());
        }

        return stringHelper.toString();
    }
}
