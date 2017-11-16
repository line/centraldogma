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

package com.linecorp.centraldogma.internal.httpapi.v1;

import static com.linecorp.centraldogma.internal.httpapi.v1.HttpApiV1Constants.COMMITS;
import static com.linecorp.centraldogma.internal.httpapi.v1.HttpApiV1Constants.COMPARE;
import static com.linecorp.centraldogma.internal.httpapi.v1.HttpApiV1Constants.CONTENTS;
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

    @JsonCreator
    public RepositoryDto(String name) {
        this.name = requireNonNull(name, "name");
    }

    @JsonCreator
    public RepositoryDto(String projectName, String repoName, Author creator,
                         Revision headRevision, long creationTimeMillis) {
        requireNonNull(projectName, "projectName");
        this.name = requireNonNull(repoName, "repoName");
        this.creator = requireNonNull(creator, "creator");
        this.headRevision = requireNonNull(headRevision, "headRevision");

        url = PROJECTS_PREFIX + '/' + projectName + REPOS + '/' + name;
        commitsUrl = createCommitsUrl(projectName, name, headRevision.text());
        compareUrl = createCompareUrl(projectName, name, headRevision.text());
        contentsUrl = createContentsUrl(projectName, name);
        createdAt = ISO_INSTANT.format(Instant.ofEpochMilli(creationTimeMillis));
    }

    // TODO(minwoox) replace with URI template processor implementing RFC6570
    private static String createCommitsUrl(String projectName, String repoName, String revision) {
        return PROJECTS_PREFIX + '/' + projectName + REPOS + '/' + repoName +
               COMMITS + "?revision=" + revision;
    }

    // TODO(minwoox) replace with URI template processor implementing RFC6570
    private static String createCompareUrl(String projectName, String repoName, String revision) {
        return PROJECTS_PREFIX + '/' + projectName + REPOS + '/' + repoName +
               COMPARE + "?from=" + revision + "&to=1";
    }

    // TODO(minwoox) replace with URI template processor implementing RFC6570
    private static String createContentsUrl(String projectName, String repoName) {
        return PROJECTS_PREFIX + '/' + projectName + REPOS + '/' + repoName + CONTENTS;
    }

    @JsonProperty("name")
    public String name() {
        return name;
    }

    @JsonProperty("creator")
    public Author creator() {
        return creator;
    }

    @JsonProperty("headRevision")
    public Revision headRevision() {
        return headRevision;
    }

    @JsonProperty("url")
    public String url() {
        return url;
    }

    @JsonProperty("commitsUrl")
    public String commitsUrl() {
        return commitsUrl;
    }

    @JsonProperty("compareUrl")
    public String compareUrl() {
        return compareUrl;
    }

    @JsonProperty("contentsUrl")
    public String contentsUrl() {
        return contentsUrl;
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
                          .add("headRevision", headRevision())
                          .add("createdAt", createdAt())
                          .toString();
    }
}
