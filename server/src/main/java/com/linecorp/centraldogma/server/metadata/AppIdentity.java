/*
 * Copyright 2025 LINE Corporation
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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * An application identity that can access Central Dogma resources.
 */
@JsonDeserialize(using = AppIdentityDeserializer.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public interface AppIdentity extends Identifiable {

    /**
     * Returns the application ID.
     */
    @JsonProperty
    String appId();

    /**
     * Returns the application identity type.
     */
    @JsonProperty("type") // TODO(minwoox): remove this annotation after applying JsonTypeInfo.
    AppIdentityType type();

    /**
     * Returns whether this application identity is for system administrators.
     */
    @JsonProperty
    boolean isSystemAdmin();

    /**
     * Returns whether this application identity allows guest access.
     */
    @JsonProperty
    boolean allowGuestAccess();

    /**
     * Returns who created this application identity when.
     */
    @JsonProperty
    UserAndTimestamp creation();

    /**
     * Returns who deactivated this application identity when.
     */
    @Nullable
    @JsonProperty
    UserAndTimestamp deactivation();

    /**
     * Returns who deleted this application identity when.
     */
    @Nullable
    @JsonProperty
    UserAndTimestamp deletion();

    /**
     * Returns whether this application identity is active.
     */
    default boolean isActive() {
        return deactivation() == null && deletion() == null;
    }

    /**
     * Returns whether this application identity is deleted.
     */
    default boolean isDeleted() {
        return deletion() != null;
    }

    /**
     * Returns a new {@link AppIdentity} instance with the specified system admin flag.
     */
    AppIdentity withSystemAdmin(boolean isSystemAdmin);
}
