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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.storage.repository.CacheableCall;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A cacheable call which is used to retrieve a value from a repository.
 */
public abstract class AbstractCacheableCall<T> implements CacheableCall<T> {

    private static final Lock[] locks;

    static {
        locks = new Lock[8192];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    final Repository repo;

    /**
     * Creates a new instance.
     */
    protected AbstractCacheableCall(Repository repo) {
        this.repo = requireNonNull(repo, "repo");
    }

    /**
     * Returns the repository which this call is associated with.
     */
    public final Repository repo() {
        return repo;
    }

    /**
     * Returns the lock which is associated with this call.
     */
    public Lock coarseGrainedLock() {
        return locks[Math.abs(hashCode() % locks.length)];
    }

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

        final AbstractCacheableCall<?> that = (AbstractCacheableCall<?>) obj;
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

    /**
     * Overrides this method to add more information to the {@link #toString()} result.
     */
    protected abstract void toString(MoreObjects.ToStringHelper helper);
}
