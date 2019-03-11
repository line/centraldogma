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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.repository.CacheableCall;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class CacheableFindLatestRevCall extends CacheableCall<Revision> {

    static final Revision EMPTY = new Revision(Integer.MIN_VALUE);

    final Revision lastKnownRevision;
    final Revision headRevision;
    final String pathPattern;
    private final int hashCode;

    CacheableFindLatestRevCall(Repository repo, Revision lastKnownRevision, Revision headRevision,
                               String pathPattern) {
        super(repo);

        this.lastKnownRevision = requireNonNull(lastKnownRevision, "lastKnownRevision");
        this.headRevision = requireNonNull(headRevision, "headRevision");
        this.pathPattern = requireNonNull(pathPattern, "pathPattern");

        hashCode = pathPattern.hashCode() * 31 + System.identityHashCode(repo);

        assert !lastKnownRevision.isRelative();
    }

    @Override
    protected int weigh(Revision value) {
        return pathPattern.length();
    }

    @Override
    public CompletableFuture<Revision> execute() {
        return repo().findLatestRevision(lastKnownRevision, pathPattern).thenApply(e -> firstNonNull(e, EMPTY));
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

        final CacheableFindLatestRevCall that = (CacheableFindLatestRevCall) o;
        return lastKnownRevision.equals(that.lastKnownRevision) &&
               headRevision.equals(that.headRevision) &&
               pathPattern.equals(that.pathPattern);
    }

    @Override
    protected void toString(ToStringHelper helper) {
        helper.add("lastKnownRevision", lastKnownRevision)
              .add("headRevision", headRevision)
              .add("pathPattern", pathPattern);
    }
}
