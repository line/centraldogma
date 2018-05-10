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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

final class CacheableHistoryCall extends CacheableCall<List<Commit>> {

    final Revision from;
    final Revision to;
    final String pathPattern;
    final int maxCommits;
    final int hashCode;

    CacheableHistoryCall(Repository repo, Revision from, Revision to, String pathPattern, int maxCommits) {
        super(repo);

        this.from = requireNonNull(from, "from");
        this.to = requireNonNull(to, "to");
        this.pathPattern = requireNonNull(pathPattern, "pathPattern");
        this.maxCommits = maxCommits;

        hashCode = (Objects.hash(from, to, pathPattern) * 31 + maxCommits) * 31 + System.identityHashCode(repo);

        assert !from.isRelative();
        assert !to.isRelative();
    }

    @Override
    int weigh(List<Commit> value) {
        int weight = 0;
        weight += pathPattern.length();
        for (Commit c : value) {
            weight += c.author().name().length() + c.author().email().length() +
                      c.summary().length() + c.detail().length();
        }
        return weight;
    }

    @Override
    CompletableFuture<List<Commit>> execute() {
        return repo.history(from, to, pathPattern, maxCommits);
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

        final CacheableHistoryCall that = (CacheableHistoryCall) o;
        return from.equals(that.from) &&
               to.equals(that.to) &&
               pathPattern.equals(that.pathPattern) &&
               maxCommits == that.maxCommits;
    }

    @Override
    void toString(ToStringHelper helper) {
        helper.add("from", from)
              .add("to", to)
              .add("pathPattern", pathPattern)
              .add("maxCommits", maxCommits);
    }
}
