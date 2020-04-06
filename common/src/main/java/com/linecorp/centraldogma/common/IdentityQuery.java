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

package com.linecorp.centraldogma.common;

import static com.linecorp.centraldogma.internal.Util.validateFilePath;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.List;

import com.google.common.base.MoreObjects;

final class IdentityQuery<T> implements Query<T> {

    private final String path;
    private final QueryType queryType;

    IdentityQuery(String path, QueryType queryType) {
        this.path = validateFilePath(path, "path");
        this.queryType = requireNonNull(queryType, "queryType");
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public QueryType type() {
        return queryType;
    }

    @Override
    public List<String> expressions() {
        return Collections.emptyList();
    }

    @Override
    public T apply(T input) {
        return requireNonNull(input, "input");
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof IdentityQuery)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        final IdentityQuery<?> that = (IdentityQuery<?>) obj;

        return path().equals(that.path());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("path", path)
                          .add("queryType", queryType)
                          .toString();
    }
}
