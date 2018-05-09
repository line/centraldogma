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

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.repository.FindOption;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

final class CacheableFindCall extends CacheableCall<Map<String, Entry<?>>> {

    final Revision revision;
    final String pathPattern;
    final Map<FindOption<?>, ?> options;
    private final int hashCode;

    CacheableFindCall(Repository repo, Revision revision, String pathPattern, Map<FindOption<?>, ?> options) {
        super(repo);

        this.revision = requireNonNull(revision, "revision");
        this.pathPattern = requireNonNull(pathPattern, "pathPattern");
        this.options = requireNonNull(options, "options");

        hashCode = Objects.hash(pathPattern, options) * 31 + System.identityHashCode(repo);

        assert !revision.isRelative();
    }

    @Override
    int weigh(Map<String, Entry<?>> value) {
        int weight = 0;
        weight += pathPattern.length();
        weight += options.size();
        for (Entry<?> e : value.values()) {
            weight += e.path().length();
            if (e.hasContent()) {
                weight += e.contentAsText().length();
            }
        }
        return weight;
    }

    @Override
    CompletableFuture<Map<String, Entry<?>>> execute() {
        return repo.find(revision, pathPattern, options);
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

        final CacheableFindCall that = (CacheableFindCall) o;
        return revision.equals(that.revision) &&
               pathPattern.equals(that.pathPattern) &&
               options.equals(that.options);
    }

    @Override
    void toString(ToStringHelper helper) {
        helper.add("revision", revision)
              .add("pathPattern", pathPattern)
              .add("options", options);
    }
}
