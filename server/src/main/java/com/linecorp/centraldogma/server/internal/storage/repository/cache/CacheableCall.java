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

import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

// XXX(trustin): Consider using reflection or AOP so that it takes less effort to add more call types.
abstract class CacheableCall<T> {

    final Repository repo;

    CacheableCall(Repository repo) {
        this.repo = requireNonNull(repo, "repo");
    }

    abstract int weigh(T value);

    abstract CompletableFuture<T> execute();

    @Override
    public int hashCode() {
        return System.identityHashCode(repo);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        if (getClass() != obj.getClass()) {
            return false;
        }

        final CacheableCall<?> that = (CacheableCall<?>) obj;
        return repo == that.repo;
    }

    @Override
    public final String toString() {
        final MoreObjects.ToStringHelper helper =
                MoreObjects.toStringHelper(this)
                           .add("repo", repo.parent().name() + '/' + repo.name());
        toString(helper);
        return helper.toString();
    }

    abstract void toString(MoreObjects.ToStringHelper helper);
}
