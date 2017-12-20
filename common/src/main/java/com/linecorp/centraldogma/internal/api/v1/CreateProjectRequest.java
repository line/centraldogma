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

import static com.linecorp.centraldogma.internal.Util.validateProjectName;

import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;

/**
 * A request to create a new project.
 */
public class CreateProjectRequest {

    private final String name;
    private final Set<String> owners;
    private final Set<String> members;

    @JsonCreator
    public CreateProjectRequest(@JsonProperty("name") String name,
                                @JsonProperty("owners") @Nullable Set<String> owners,
                                @JsonProperty("members") @Nullable Set<String> members) {
        this.name = validateProjectName(name, "name");
        this.owners = owners != null ? ImmutableSet.copyOf(owners) : ImmutableSet.of();
        this.members = members != null ? ImmutableSet.copyOf(members) : ImmutableSet.of();
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public Set<String> owners() {
        return owners;
    }

    @JsonProperty
    public Set<String> members() {
        return members;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name())
                          .add("owners", owners())
                          .add("members", members())
                          .toString();
    }
}
