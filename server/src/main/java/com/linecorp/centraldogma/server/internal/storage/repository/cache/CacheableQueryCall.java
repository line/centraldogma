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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

final class CacheableQueryCall extends CacheableCall<Entry<?>> {

    static final Entry<?> EMPTY = Entry.ofDirectory(new Revision(Integer.MAX_VALUE), "/");

    final Revision revision;
    final Query<?> query;
    final int hashCode;

    @Nullable
    Entry<?> computedValue;

    CacheableQueryCall(Repository repo, Revision revision, Query<?> query) {
        super(repo);
        this.revision = requireNonNull(revision, "revision");
        this.query = requireNonNull(query, "query");

        hashCode = Objects.hash(revision, query) * 31 + System.identityHashCode(repo);

        assert !revision.isRelative();
    }

    @Override
    int weigh(Entry<?> value) {
        int weight = 0;
        weight += query.path().length();
        for (String e : query.expressions()) {
            weight += e.length();
        }
        if (value != null && value.hasContent()) {
            weight += value.contentAsText().length();
        }
        return weight;
    }

    @Override
    CompletableFuture<Entry<?>> execute() {
        checkState(computedValue != null, "computedValue is not set yet.");
        return CompletableFuture.completedFuture(computedValue);
    }

    void computedValue(Entry<?> computedValue) {
        checkState(this.computedValue == null, "computedValue is already set.");
        this.computedValue = requireNonNull(computedValue, "computedValue");
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

        final CacheableQueryCall that = (CacheableQueryCall) o;
        return revision.equals(that.revision) &&
               query.equals(that.query);
    }

    @Override
    void toString(ToStringHelper helper) {
        helper.add("revision", revision)
              .add("query", query);
    }
}
