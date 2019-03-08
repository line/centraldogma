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

package com.linecorp.centraldogma.client;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Revision;

/**
 * An immutable holder of repository information.
 */
public final class RepositoryInfo {

    // TODO(trustin): Add createdAt and creator property once we remove the Thrift API.

    private final String name;
    private final Revision headRevision;

    /**
     * Creates a new instance with the specified repository name and the latest {@link Revision} of the
     * repository.
     */
    public RepositoryInfo(String name, Revision headRevision) {
        this.name = requireNonNull(name, "name");
        this.headRevision = requireNonNull(headRevision, "headRevision");
    }

    /**
     * Returns the name of the repository.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the latest {@link Revision} of the repository.
     */
    public Revision headRevision() {
        return headRevision;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof RepositoryInfo)) {
            return false;
        }

        final RepositoryInfo that = (RepositoryInfo) o;
        return name.equals(that.name) && headRevision.equals(that.headRevision);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + headRevision.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("headRevision", headRevision)
                          .toString();
    }
}
