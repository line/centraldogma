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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * Specifies details of a {@link Repository}.
 */
public class RepoInfo {

    private final String name;
    private final UserAndTimestamp creation;

    @JsonCreator
    public RepoInfo(@JsonProperty("name") String name,
                    @JsonProperty("creation") UserAndTimestamp creation) {
        this.name = requireNonNull(name, "name");
        this.creation = requireNonNull(creation, "creation");
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public UserAndTimestamp creation() {
        return creation;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name())
                          .add("creation", creation())
                          .toString();
    }
}
