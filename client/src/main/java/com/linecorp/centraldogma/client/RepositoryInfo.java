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

import com.linecorp.centraldogma.common.Commit;

/**
 * An immutable holder of repository information.
 */
public final class RepositoryInfo {

    private final String name;
    private final Commit lastCommit;

    /**
     * Creates a new instance with the specified repository name and the last {@link Commit} of the repository.
     */
    public RepositoryInfo(String name, Commit lastCommit) {
        this.name = requireNonNull(name, "name");
        this.lastCommit = requireNonNull(lastCommit, "lastCommit");
    }

    /**
     * Returns the name of the repository.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the last {@link Commit} of the repository.
     */
    public Commit lastCommit() {
        return lastCommit;
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
        return name.equals(that.name) && lastCommit.equals(that.lastCommit);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + lastCommit.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("lastCommit", lastCommit)
                          .toString();
    }
}
