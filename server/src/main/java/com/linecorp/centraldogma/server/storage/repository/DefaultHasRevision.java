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

package com.linecorp.centraldogma.server.storage.repository;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Revision;

final class DefaultHasRevision<T> implements HasRevision<T> {

    private final T object;
    private final Revision revision;

    DefaultHasRevision(T object, Revision revision) {
        this.object = object;
        this.revision = revision;
    }

    @Override
    public Revision revision() {
        return revision;
    }

    @Override
    public T object() {
        return object;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DefaultHasRevision)) {
            return false;
        }
        final DefaultHasRevision<?> that = (DefaultHasRevision<?>) o;
        return object.equals(that.object) && revision.equals(that.revision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(object, revision);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("object", object)
                          .add("revision", revision)
                          .toString();
    }
}
