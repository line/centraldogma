/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.centraldogma.server.internal.api;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * A request to update the settings of a project. A {@code null} field is left unchanged.
 */
final class UpdateProjectSettingsRequest {

    @Nullable
    private final Boolean allowPublicRepositories;

    @JsonCreator
    UpdateProjectSettingsRequest(
            @JsonProperty("allowPublicRepositories") @Nullable Boolean allowPublicRepositories) {
        this.allowPublicRepositories = allowPublicRepositories;
    }

    /**
     * Returns whether the repositories of the project can be made public, or {@code null} if unchanged.
     */
    @Nullable
    @JsonProperty
    public Boolean allowPublicRepositories() {
        return allowPublicRepositories;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UpdateProjectSettingsRequest)) {
            return false;
        }
        final UpdateProjectSettingsRequest that = (UpdateProjectSettingsRequest) o;
        return Objects.equals(allowPublicRepositories, that.allowPublicRepositories);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(allowPublicRepositories);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("allowPublicRepositories", allowPublicRepositories())
                          .toString();
    }
}
