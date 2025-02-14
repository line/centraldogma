/*
 * Copyright 2019 LINE Corporation
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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import org.eclipse.jgit.attributes.Attribute;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.revwalk.RevTree;

import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.server.internal.storage.repository.AbstractCacheableCall;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class CacheableCompareTreesCall extends AbstractCacheableCall<List<DiffEntry>> {

    private static final int SHA1_LEN = 20;

    // Use its own lock instead of the locks in AbstractCacheableCall because the caller might already have
    // the lock in AbstractCacheableCall which can cause a deadlock.
    private static final Lock[] locks;

    static {
        locks = new Lock[64];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    @Nullable
    private final RevTree treeA;
    @Nullable
    private final RevTree treeB;
    private final int hashCode;

    CacheableCompareTreesCall(Repository repo, @Nullable RevTree treeA, @Nullable RevTree treeB) {
        super(repo);

        this.treeA = treeA;
        this.treeB = treeB;
        hashCode = Objects.hash(treeA, treeB) * 31 + System.identityHashCode(repo);
    }

    @Override
    public Lock coarseGrainedLock() {
        return locks[Math.abs(hashCode() % locks.length)];
    }

    @Override
    public int weigh(List<DiffEntry> value) {
        int weight = SHA1_LEN * 2;
        for (DiffEntry e : value) {
            if (e.getOldId() != null) {
                weight += SHA1_LEN;
            }
            if (e.getNewId() != null) {
                weight += SHA1_LEN;
            }
            if (e.getOldPath() != null) {
                weight += e.getOldPath().length();
            }
            if (e.getNewPath() != null) {
                weight += e.getNewPath().length();
            }
            final Attribute attr = e.getDiffAttribute();
            if (attr != null) {
                if (attr.getKey() != null) {
                    weight += attr.getKey().length();
                }
                if (attr.getValue() != null) {
                    weight += attr.getValue().length();
                }
            }
        }
        return weight;
    }

    /**
     * Never invoked because {@link GitRepository} produces the value of this call.
     */
    @Override
    public CompletableFuture<List<DiffEntry>> execute() {
        throw new IllegalStateException();
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

        final CacheableCompareTreesCall that = (CacheableCompareTreesCall) o;
        return Objects.equals(treeA, that.treeA) &&
               Objects.equals(treeB, that.treeB);
    }

    @Override
    protected void toString(ToStringHelper helper) {
        helper.add("treeA", treeA != null ? treeA.getName() : null)
              .add("treeB", treeB != null ? treeB.getName() : null);
    }
}
