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

package com.linecorp.centraldogma.server.internal.storage.repository.cache;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.repository.CacheableCall;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

final class CacheableMultiDiffCall extends CacheableCall<Map<String, Change<?>>> {

    final Revision from;
    final Revision to;
    final String pathPattern;
    final int hashCode;

    CacheableMultiDiffCall(Repository repo, Revision from, Revision to, String pathPattern) {
        super(repo);

        this.from = requireNonNull(from, "from");
        this.to = requireNonNull(to, "to");
        this.pathPattern = requireNonNull(pathPattern, "pathPattern");

        hashCode = Objects.hash(from, to, pathPattern) * 31 + System.identityHashCode(repo);

        assert !from.isRelative();
        assert !to.isRelative();
    }

    @Override
    protected int weigh(Map<String, Change<?>> value) {
        int weight = 0;
        weight += pathPattern.length();
        for (Change<?> e : value.values()) {
            weight += e.path().length();
            final String content = e.contentAsText();
            if (content != null) {
                weight += content.length();
            }
        }
        return weight;
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> execute() {
        return repo().diff(from, to, pathPattern);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }

        final CacheableMultiDiffCall that = (CacheableMultiDiffCall) o;
        return from.equals(that.from) &&
               to.equals(that.to) &&
               pathPattern.equals(that.pathPattern);
    }

    @Override
    protected void toString(ToStringHelper helper) {
        helper.add("from", from)
              .add("to", to)
              .add("pathPattern", pathPattern);
    }
}
