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

package com.linecorp.centraldogma.server.internal.storage.repository;

import static java.util.Objects.requireNonNull;

import com.linecorp.centraldogma.common.Revision;

/**
 * An interface that provides a {@link Revision} with an object.
 */
public interface HasRevision<T> {

    /**
     * Creates a new instance with the specified object and revision.
     */
    static <T> HasRevision<T> of(T object, Revision revision) {
        requireNonNull(object, "object");
        requireNonNull(revision, "revision");
        return new DefaultHasRevision<>(object, revision);
    }

    /**
     * Returns the {@link Revision}.
     */
    Revision revision();

    /**
     * Returns the object.
     */
    T object();
}
