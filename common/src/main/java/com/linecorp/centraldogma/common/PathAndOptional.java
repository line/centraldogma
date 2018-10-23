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
 * A holder that contains a {@code path} and {@code isOptional} which indicates whether the path is required.
 */
public class PathAndOptional {

    private final String path;

    private final boolean isOptional;

    /**
     * Creates a new instance.
     */
    public PathAndOptional(String path, boolean isOptional) {
        this.path = requireNonNull(path, "path");
        this.isOptional = isOptional;
    }

    /**
     * Returns the path.
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns {@code true} if the path is optional.
     */
    public boolean isOptional() {
        return isOptional;
    }

    @Override
    public int hashCode() {
        return path.hashCode() * 31 + Boolean.hashCode(isOptional);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PathAndOptional)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        final PathAndOptional that = (PathAndOptional) o;
        return path.equals(that.path) && isOptional == that.isOptional;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("path", path)
                          .add("isOptional", isOptional)
                          .toString();
    }
}
