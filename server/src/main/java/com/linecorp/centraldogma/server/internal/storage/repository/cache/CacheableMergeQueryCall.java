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

package com.linecorp.centraldogma.server.internal.storage.repository.cache;

import static com.linecorp.centraldogma.internal.Util.validateJsonFilePath;
import static com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache.logger;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.repository.AbstractCacheableCall;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class CacheableMergeQueryCall<T> extends AbstractCacheableCall<MergedEntry<T>> {

    private final Revision revision;
    private final MergeQuery<T> query;
    private final int hashCode;

    CacheableMergeQueryCall(Repository repo, Revision revision, MergeQuery<T> query) {
        super(repo);
        this.revision = requireNonNull(revision, "revision");
        this.query = requireNonNull(query, "query");
        // Only JSON files can currently be merged.
        query.mergeSources().forEach(path -> validateJsonFilePath(path.path(), "path"));

        hashCode = Objects.hash(revision, query) * 31 + System.identityHashCode(repo);

        assert !revision.isRelative();
    }

    @Override
    public int weigh(MergedEntry<T> value) {
        int weight = 0;
        final List<MergeSource> mergeSources = query.mergeSources();
        weight += mergeSources.size();
        for (MergeSource mergeSource : mergeSources) {
            weight += mergeSource.path().length();
        }
        final List<String> expressions = query.expressions();
        weight += expressions.size();
        for (String expression : expressions) {
            weight += expression.length();
        }
        if (value != null) {
            weight += value.contentAsText().length();
        }
        return weight;
    }

    @Override
    public CompletableFuture<MergedEntry<T>> execute() {
        logger.debug("Cache miss: {}", this);
        return repo().mergeFiles(revision, query);
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

        final CacheableMergeQueryCall<?> that = (CacheableMergeQueryCall<?>) o;
        return revision.equals(that.revision) &&
               query.equals(that.query);
    }

    @Override
    protected void toString(ToStringHelper helper) {
        helper.add("revision", revision)
              .add("query", query);
    }
}
