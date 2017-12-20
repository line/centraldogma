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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;

/**
 * Roles of a {@link User} in a project.
 */
public enum ProjectRole {
    OWNER,
    MEMBER,
    GUEST;

    /**
     * Returns a {@link ProjectRole} matched with the specified {@code str}.
     *
     * @throws IllegalArgumentException if there is no matched {@link ProjectRole}.
     */
    public static ProjectRole of(String str) {
        requireNonNull(str, "str");
        return valueOf(str.toUpperCase());
    }

    /**
     * Returns a {@link ProjectRole} matched with the specified {@link JsonNode}.
     *
     * @throws IllegalArgumentException if there is no matched {@link ProjectRole}.
     */
    public static ProjectRole of(JsonNode node) {
        requireNonNull(node, "node");
        try {
            return Jackson.treeToValue(node, ProjectRole.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
