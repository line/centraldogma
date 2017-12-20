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

package com.linecorp.centraldogma.server.internal.metadata;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.EntryNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryNotFoundException;

/**
 * Specifies details of a {@link Project}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public class ProjectMetadata implements Identifiable {

    /**
     * A project name.
     */
    private final String name;

    /**
     * Repositories of this project.
     */
    private final Map<String, RepositoryMetadata> repos;

    /**
     * Members of this project.
     */
    private final Map<String, Member> members;

    /**
     * Tokens which belong to this project.
     */
    private final Map<String, TokenRegistration> tokens;

    /**
     * Specifies when this project is created by whom.
     */
    private final UserAndTimestamp creation;

    /**
     * Specifies when this project is removed by whom.
     */
    @Nullable
    private final UserAndTimestamp removal;

    @JsonCreator
    public ProjectMetadata(@JsonProperty("name") String name,
                           @JsonProperty("repos") Map<String, RepositoryMetadata> repos,
                           @JsonProperty("members") Map<String, Member> members,
                           @JsonProperty("tokens") Map<String, TokenRegistration> tokens,
                           @JsonProperty("creation") UserAndTimestamp creation,
                           @JsonProperty("removal") @Nullable UserAndTimestamp removal) {
        this.name = requireNonNull(name, "name");
        this.repos = requireNonNull(repos, "repos");
        this.members = requireNonNull(members, "members");
        this.tokens = requireNonNull(tokens, "tokens");
        this.creation = requireNonNull(creation, "creation");
        this.removal = removal;
    }

    @Override
    public String id() {
        return name;
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public Map<String, RepositoryMetadata> repos() {
        return repos;
    }

    @JsonProperty
    public Map<String, Member> members() {
        return members;
    }

    @JsonProperty
    public Map<String, TokenRegistration> tokens() {
        return tokens;
    }

    @JsonProperty
    public UserAndTimestamp creation() {
        return creation;
    }

    @Nullable
    @JsonProperty
    public UserAndTimestamp removal() {
        return removal;
    }

    public RepositoryMetadata repo(String repoName) {
        final RepositoryMetadata repositoryMetadata =
                repos.get(requireNonNull(repoName, "repoName"));
        if (repositoryMetadata != null) {
            return repositoryMetadata;
        }
        throw new RepositoryNotFoundException(repoName);
    }

    public Member member(String memberId) {
        final Member member = memberOrDefault(memberId, null);
        if (member != null) {
            return member;
        }
        throw new EntryNotFoundException(memberId);
    }

    public Member memberOrDefault(String memberId, @Nullable Member defaultMember) {
        final Member member = members.get(requireNonNull(memberId, "memberId"));
        if (member != null) {
            return member;
        }
        return defaultMember;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name())
                          .add("repos", repos())
                          .add("members", members())
                          .add("tokens", tokens())
                          .add("creation", creation())
                          .add("removal", removal())
                          .toString();
    }
}
