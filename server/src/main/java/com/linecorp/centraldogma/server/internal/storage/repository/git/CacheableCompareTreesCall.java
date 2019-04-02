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

import javax.annotation.Nullable;

import org.eclipse.jgit.attributes.Attribute;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.server.internal.storage.repository.CacheableCall;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

final class CacheableCompareTreesCall extends CacheableCall<List<DiffEntry>> {

    private static final int SHA1_LEN = 20;

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
    protected int weigh(List<DiffEntry> value) {
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

    @Override
    public CompletableFuture<List<DiffEntry>> execute() {
        return ((GitRepository) repo()).compareTreesUncached(treeA, treeB, TreeFilter.ALL);
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
