/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.centraldogma.server.metadata;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.HasWeight;

/**
 * Specifies details of a {@link Project}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public class ProjectMetadata implements Identifiable, HasWeight {

    public static final ProjectMetadata DOGMA_PROJECT_METADATA =
            new ProjectMetadata("dogma",
                                ImmutableMap.of(),
                                ImmutableMap.of(),
                                null,
                                ImmutableMap.of(),
                                new UserAndTimestamp(User.SYSTEM.id()),
                                null);

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
    private final Map<String, TokenRegistration> appIds;

    /**
     * Specifies when this project is created by whom.
     */
    private final UserAndTimestamp creation;

    /**
     * Specifies when this project is removed by whom.
     */
    @Nullable
    private final UserAndTimestamp removal;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public ProjectMetadata(@JsonProperty("name") String name,
                           @JsonProperty("repos") Map<String, RepositoryMetadata> repos,
                           @JsonProperty("members") Map<String, Member> members,
                           @JsonProperty("tokens") @Nullable Map<String, TokenRegistration> tokens,
                           @JsonProperty("appIds") @Nullable Map<String, TokenRegistration> appIds,
                           @JsonProperty("creation") UserAndTimestamp creation,
                           @JsonProperty("removal") @Nullable UserAndTimestamp removal) {
        this.name = requireNonNull(name, "name");
        this.repos = ImmutableMap.copyOf(requireNonNull(repos, "repos"));
        this.members = ImmutableMap.copyOf(requireNonNull(members, "members"));
        if (tokens == null && appIds == null) {
            throw new IllegalArgumentException("tokens or appIds are required");
        }

        if (appIds != null) {
            this.appIds = ImmutableMap.copyOf(appIds);
        } else {
            this.appIds = ImmutableMap.copyOf(tokens);
        }

        this.creation = requireNonNull(creation, "creation");
        this.removal = removal;
    }

    @Override
    public String id() {
        return name;
    }

    /**
     * Returns the project name.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the metadata of the repositories in this project.
     */
    @JsonProperty
    public Map<String, RepositoryMetadata> repos() {
        return repos;
    }

    /**
     * Returns the {@link Member}s of this project.
     */
    @JsonProperty
    public Map<String, Member> members() {
        return members;
    }

    /**
     * Returns the {@link TokenRegistration}s of this project.
     */
    @JsonProperty
    public Map<String, TokenRegistration> appIds() {
        return appIds;
    }

    /**
     * Returns who created this project when.
     */
    @JsonProperty
    public UserAndTimestamp creation() {
        return creation;
    }

    /**
     * Returns who removed this project when.
     */
    @Nullable
    @JsonProperty
    public UserAndTimestamp removal() {
        return removal;
    }

    /**
     * Returns the {@link RepositoryMetadata} of the specified repository in this project.
     */
    public RepositoryMetadata repo(String repoName) {
        final RepositoryMetadata repositoryMetadata =
                repos.get(requireNonNull(repoName, "repoName"));
        if (repositoryMetadata != null) {
            return repositoryMetadata;
        }
        throw RepositoryNotFoundException.of(name, repoName);
    }

    /**
     * Returns the {@link Member} of the specified ID in this project.
     */
    public Member member(String memberId) {
        final Member member = memberOrDefault(memberId, null);
        if (member != null) {
            return member;
        }
        throw new MemberNotFoundException(memberId, name());
    }

    /**
     * Returns the {@link Member} of the specified ID in this project.
     * {@code defaultMember} is returned if there is no such member.
     */
    @Nullable
    public Member memberOrDefault(String memberId, @Nullable Member defaultMember) {
        final Member member = members.get(requireNonNull(memberId, "memberId"));
        if (member != null) {
            return member;
        }
        return defaultMember;
    }

    /**
     * Returns the {@link TokenRegistration} of the specified application ID in this project.
     */
    @Nullable
    public TokenRegistration tokenOrDefault(String appId, @Nullable TokenRegistration defaultToken) {
        final TokenRegistration token = appIds.get(requireNonNull(appId, "appId"));
        if (token != null) {
            return token;
        }
        return defaultToken;
    }

    @Override
    public int weight() {
        int weight = name().length();
        for (RepositoryMetadata repo : repos.values()) {
            weight += repo.weight();
        }
        for (Member member : members.values()) {
            weight += member.weight();
        }
        for (TokenRegistration token : appIds.values()) {
            weight += token.weight();
        }

        return weight;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProjectMetadata)) {
            return false;
        }
        final ProjectMetadata that = (ProjectMetadata) o;
        return name.equals(that.name) &&
               repos.equals(that.repos) &&
               members.equals(that.members) &&
               appIds.equals(that.appIds) &&
               creation.equals(that.creation) &&
               Objects.equals(removal, that.removal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, repos, members, appIds, creation, removal);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name())
                          .add("repos", repos())
                          .add("members", members())
                          .add("appIds", appIds())
                          .add("creation", creation())
                          .add("removal", removal())
                          .toString();
    }

    /**
     * Returns a new {@link ProjectMetadata} without the Dogma repository.
     */
    public ProjectMetadata withoutDogmaRepo() {
        if (!repos().containsKey(Project.REPO_DOGMA)) {
            return this;
        }
        final Map<String, RepositoryMetadata> filtered =
                repos().entrySet().stream().filter(entry -> !Project.REPO_DOGMA.equals(entry.getKey()))
                       .collect(toImmutableMap(Entry::getKey, Entry::getValue));
        return new ProjectMetadata(name(),
                                   filtered,
                                   members(),
                                   null,
                                   appIds(),
                                   creation(),
                                   removal());
    }
}
