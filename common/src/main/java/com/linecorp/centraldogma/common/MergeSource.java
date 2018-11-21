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

package com.linecorp.centraldogma.common;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

/**
 * A merge source that contains a {@code path} and {@code isOptional} which indicates whether the path
 * is required or not.
 */
public final class MergeSource {

    /**
     * Returns a newly-created {@link MergeSource} which contains a required path.
     */
    public static MergeSource ofRequired(String path) {
        return new MergeSource(path, false);
    }

    /**
     * Returns a newly-created {@link MergeSource} which contains an optional path.
     */
    public static MergeSource ofOptional(String path) {
        return new MergeSource(path, true);
    }

    private final String path;

    private final boolean optional;

    /**
     * Creates a new instance.
     */
    private MergeSource(String path, boolean optional) {
        this.path = requireNonNull(path, "path");
        this.optional = optional;
    }

    /**
     * Returns the path.
     */
    public String path() {
        return path;
    }

    /**
     * Returns {@code true} if the path is optional.
     */
    public boolean isOptional() {
        return optional;
    }

    @Override
    public int hashCode() {
        return path.hashCode() * 31 + Boolean.hashCode(optional);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MergeSource)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        final MergeSource that = (MergeSource) o;
        return path.equals(that.path) && optional == that.optional;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("path", path)
                          .add("optional", optional)
                          .toString();
    }
}
