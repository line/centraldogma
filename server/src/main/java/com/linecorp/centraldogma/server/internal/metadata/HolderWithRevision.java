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

import com.linecorp.centraldogma.common.Revision;

final class HolderWithRevision<T> {

    static <T> HolderWithRevision<T> of(T object, Revision revision) {
        return new HolderWithRevision<>(object, revision);
    }

    private final T object;
    private final Revision revision;

    HolderWithRevision(T object, Revision revision) {
        this.object = requireNonNull(object, "object");
        this.revision = requireNonNull(revision, "revision");
    }

    public T object() {
        return object;
    }

    public Revision revision() {
        return revision;
    }
}
