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

package com.linecorp.centraldogma.client;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;

/**
 * An immutable holder of a {@link Commit} and its {@link Change}s.
 */
public final class CommitAndChanges<T> {

    private final Commit commit;
    private final List<Change<T>> changes;

    /**
     * Creates a new instance with the specified {@link Commit} and {@link Change}s.
     */
    public CommitAndChanges(Commit commit, Iterable<? extends Change<T>> changes) {
        this.commit = requireNonNull(commit, "commit");
        this.changes = ImmutableList.copyOf(requireNonNull(changes, "changes"));
    }

    /**
     * Returns the {@link Commit}.
     */
    public Commit commit() {
        return commit;
    }

    /**
     * Returns the {@link Change}s.
     */
    public List<Change<T>> changes() {
        return changes;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("commit", commit)
                          .add("changes", changes)
                          .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommitAndChanges<?>)) {
            return false;
        }
        final CommitAndChanges<?> that = (CommitAndChanges<?>) o;
        return commit.equals(that.commit) && changes.equals(that.changes);
    }

    @Override
    public int hashCode() {
        return 31 * commit.hashCode() + changes.hashCode();
    }
}
