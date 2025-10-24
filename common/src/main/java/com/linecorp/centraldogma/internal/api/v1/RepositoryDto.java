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

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.RepositoryStatus;
import com.linecorp.centraldogma.common.Revision;

@JsonInclude(Include.NON_NULL)
public class RepositoryDto {

    public static RepositoryDto removed(String name) {
        requireNonNull(name, "name");
        return new RepositoryDto(name);
    }

    private final String name;

    @Nullable
    private final Author creator;

    @Nullable
    private final Revision headRevision;

    @Nullable
    private final String url;

    @Nullable
    private final String createdAt;

    @Nullable
    private final RepositoryStatus status;

    RepositoryDto(String name) {
        this.name = requireNonNull(name, "name");
        creator = null;
        headRevision = null;
        url = null;
        createdAt = null;
        status = null;
    }

    public RepositoryDto(String projectName, String repoName, Author creator, Revision headRevision,
                         long creationTimeMillis, RepositoryStatus status) {
        this(requireNonNull(repoName, "repoName"), requireNonNull(creator, "creator"),
             requireNonNull(headRevision, "headRevision"),
             PROJECTS_PREFIX + '/' + requireNonNull(projectName, "projectName") + REPOS + '/' + repoName,
             ISO_INSTANT.format(Instant.ofEpochMilli(creationTimeMillis)), requireNonNull(status, "status"));
    }

    @JsonCreator
    public RepositoryDto(@JsonProperty("name") String name,
                         @JsonProperty("creator") @Nullable Author creator,
                         @JsonProperty("headRevision") @Nullable Revision headRevision,
                         @JsonProperty("url") @Nullable String url,
                         @JsonProperty("createdAt") @Nullable String createdAt,
                         @JsonProperty("status") @Nullable RepositoryStatus status) {
        this.name = requireNonNull(name, "name");
        this.creator = creator;
        this.headRevision = headRevision;
        this.url = url;
        this.createdAt = createdAt;
        this.status = status;
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
    @JsonProperty("createdAt")
    public String createdAt() {
        return createdAt;
    }

    @Nullable
    @JsonProperty("status")
    public RepositoryStatus status() {
        return status;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("name", name())
                          .add("creator", creator())
                          .add("headRevision", headRevision())
                          .add("url", url())
                          .add("createdAt", createdAt())
                          .add("status", status())
                          .toString();
    }
}
