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

package com.linecorp.centraldogma.server.internal.admin.model;

import static java.util.Objects.requireNonNull;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.server.internal.storage.project.Project;

/**
 * Specifies details of a {@link Project}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public class ProjectInfo {

    private final String name;
    private final List<RepoInfo> repos;
    private final List<MemberInfo> members;
    private final List<TokenInfo> tokens;

    private final UserAndTimestamp creation;

    @Nullable
    private final UserAndTimestamp removal;

    public ProjectInfo(String name, UserAndTimestamp creation, List<MemberInfo> members) {
        this(name, ImmutableList.of(), members, ImmutableList.of(), creation, null);
    }

    @JsonCreator
    public ProjectInfo(@JsonProperty("name") String name,
                       @JsonProperty("repos") List<RepoInfo> repos,
                       @JsonProperty("members") List<MemberInfo> members,
                       @JsonProperty("tokens") List<TokenInfo> tokens,
                       @JsonProperty("creation") UserAndTimestamp creation,
                       @JsonProperty("removal") @Nullable UserAndTimestamp removal) {
        this.name = requireNonNull(name, "name");
        this.repos = ImmutableList.sortedCopyOf(Comparator.comparing(RepoInfo::name),
                                                requireNonNull(repos, "repos"));
        this.members = ImmutableList.sortedCopyOf(Comparator.comparing(MemberInfo::login),
                                                  requireNonNull(members, "members"));
        this.tokens = ImmutableList.sortedCopyOf(Comparator.comparing(TokenInfo::appId),
                                                 requireNonNull(tokens, "tokens"));
        this.creation = requireNonNull(creation, "creation");
        this.removal = removal;
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public List<RepoInfo> repos() {
        return repos;
    }

    @JsonProperty
    public List<MemberInfo> members() {
        return members;
    }

    @JsonProperty
    public List<TokenInfo> tokens() {
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

    @JsonProperty
    public boolean isRemoved() {
        return removal() != null;
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
                          .add("removed", isRemoved())
                          .toString();
    }

    public ProjectInfo duplicateWithRepos(List<RepoInfo> newRepos) {
        return new ProjectInfo(name(),
                               requireNonNull(newRepos, "newRepos"),
                               members(),
                               tokens(),
                               creation(),
                               removal());
    }

    public ProjectInfo duplicateWithMembers(List<MemberInfo> newMembers) {
        return new ProjectInfo(name(),
                               repos(),
                               requireNonNull(newMembers, "newMembers"),
                               tokens(),
                               creation(),
                               removal());
    }

    public ProjectInfo duplicateWithTokens(List<TokenInfo> newTokens) {
        return new ProjectInfo(name(),
                               repos(),
                               members(),
                               requireNonNull(newTokens, "newTokens"),
                               creation(),
                               removal());
    }

    public ProjectInfo duplicateWithoutTokenSecret() {
        return duplicateWithTokens(tokens().stream()
                                           .map(TokenInfo::withoutSecret)
                                           .collect(Collectors.toList()));
    }

    public static void ensureMember(ProjectInfo project, String login) {
        requireNonNull(project, "project");
        requireNonNull(login, "login");
        if (project.members().stream().noneMatch(member -> member.login().equals(login))) {
            throw new IllegalArgumentException(login + "is not a member of a project " + project.name());
        }
    }
}
