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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

final class CacheableSingleDiffCall extends CacheableCall<Change<?>> {

    final Revision from;
    final Revision to;
    final Query<?> query;
    final int hashCode;

    CacheableSingleDiffCall(Repository repo, Revision from, Revision to, Query<?> query) {
        super(repo);

        this.from = requireNonNull(from, "from");
        this.to = requireNonNull(to, "to");
        this.query = requireNonNull(query, "query");

        hashCode = Objects.hash(from, to, query) * 31 + System.identityHashCode(repo);

        assert !from.isRelative();
        assert !to.isRelative();
    }

    @Override
    int weigh(Change<?> value) {
        int weight = 0;
        weight += query.path().length();
        for (String e : query.expressions()) {
            weight += e.length();
        }
        weight += value.path().length();
        final String content = value.contentAsText();
        if (content != null) {
            weight += content.length();
        }
        return weight;
    }

    @Override
    CompletableFuture<Change<?>> execute() {
        return repo.diff(from, to, query);
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

        final CacheableSingleDiffCall that = (CacheableSingleDiffCall) o;
        return from.equals(that.from) &&
               to.equals(that.to) &&
               query.equals(that.query);
    }

    @Override
    void toString(ToStringHelper helper) {
        helper.add("from", from)
              .add("to", to)
              .add("query", query);
    }
}
