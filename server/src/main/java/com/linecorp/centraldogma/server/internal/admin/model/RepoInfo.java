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

import java.util.Map;
import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * Specifies details of a {@link Repository}.
 */
public class RepoInfo {

    /**
     * A name of this repository.
     */
    private final String name;

    /**
     * A default permission of this repository which is based on a {@link ProjectRole} of a user.
     */
    private final DefaultPermission defaultPermission;

    /**
     * A map of username and {@link Permission} who has a permission specified by a owner.
     */
    private final Map<String, Permission> privilegedMember;

    /**
     * Specifies when this repository is created by whom.
     */
    private final UserAndTimestamp creation;

    /**
     * Creates a new repository with default properties.
     */
    public RepoInfo(String name, UserAndTimestamp creation) {
        this(name, new DefaultPermission(), ImmutableMap.of(), creation);
    }

    @JsonCreator
    public RepoInfo(@JsonProperty("name") String name,
                    @JsonProperty("defaultPermission") DefaultPermission defaultPermission,
                    @JsonProperty("privilegedMember") Map<String, Permission> privilegedMember,
                    @JsonProperty("creation") UserAndTimestamp creation) {
        this.name = requireNonNull(name, "name");
        this.defaultPermission = requireNonNull(defaultPermission, "defaultPermission");
        this.privilegedMember = ImmutableMap.copyOf(requireNonNull(privilegedMember, "privilegedMember"));
        this.creation = requireNonNull(creation, "creation");
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public DefaultPermission defaultPermission() {
        return defaultPermission;
    }

    @JsonProperty
    public Map<String, Permission> privilegedMember() {
        return privilegedMember;
    }

    @JsonProperty
    public UserAndTimestamp creation() {
        return creation;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name())
                          .add("defaultPermission", defaultPermission())
                          .add("privilegedMember", privilegedMember())
                          .add("creation", creation())
                          .toString();
    }

    public RepoInfo duplicateWithDefaultPermission(DefaultPermission defaultPermission) {
        return new RepoInfo(name(),
                            requireNonNull(defaultPermission, "defaultPermission"),
                            privilegedMember(),
                            creation());
    }

    public RepoInfo duplicateWithPrivilegedMember(Map<String, Permission> privilegedMember) {
        return new RepoInfo(name(),
                            defaultPermission(),
                            requireNonNull(privilegedMember, "privilegedMember"),
                            creation());
    }

    public static void ensureContainPrivilegedMember(RepoInfo repo, String username) {
        requireNonNull(repo, "repo");
        requireNonNull(username, "username");
        if (!repo.privilegedMember().containsKey(username)) {
            // TODO(hyangtack) Use other exception?
            throw new IllegalArgumentException(username + " is not a privileged member of " + repo.name());
        }
    }

    public static void ensureNotContainPrivilegedMember(RepoInfo repo, String username) {
        requireNonNull(repo, "repo");
        requireNonNull(username, "username");
        if (repo.privilegedMember().containsKey(username)) {
            // TODO(hyangtack) Use other exception?
            throw new IllegalArgumentException(username + " already exists in " + repo.name());
        }
    }

    public static ImmutableMap.Builder<String, Permission> collectPrivilegedMember(
            RepoInfo repo, BiFunction<String, Permission, Boolean> filter) {
        final ImmutableMap.Builder<String, Permission> builder =
                ImmutableMap.builder();
        repo.privilegedMember().forEach((m, p) -> {
            if (filter.apply(m, p)) {
                builder.put(m, p);
            }
        });
        return builder;
    }
}
