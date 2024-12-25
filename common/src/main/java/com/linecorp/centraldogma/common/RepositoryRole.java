/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.common;

import static java.util.Objects.requireNonNull;

/**
 * Roles for a repository.
 */
public enum RepositoryRole {
    /**
     * Able to read a file from a repository.
     */
    READ,
    /**
     * Able to write a file to a repository.
     */
    WRITE,
    /**
     * Able to manage a repository.
     */
    ADMIN;

    /**
     * Returns {@code true} if this {@link RepositoryRole} has the specified {@link RepositoryRole}.
     */
    public boolean has(RepositoryRole other) {
        requireNonNull(other, "other");
        if (this == ADMIN) {
            return true;
        }
        if (this == WRITE) {
            return other != ADMIN;
        }
        // this == READ
        return this == other;
    }
}
