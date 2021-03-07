/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.centraldogma.server.command;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;

/**
 * Result of a {@link PreviewDiffApplyingPushCommand} commit.
 */
public final class CommitResult {

    private final Revision revision;
    private final List<Change<?>> changes;

    /**
     * Returns a {@link CommitResult}.
     */
    public static CommitResult of(Revision revision, Iterable<Change<?>> changes) {
        return new CommitResult(revision, changes);
    }

    private CommitResult(Revision revision, Iterable<Change<?>> changes) {
        this.revision = requireNonNull(revision, "revision");
        this.changes = ImmutableList.copyOf(requireNonNull(changes, "changes"));
    }

    /**
     * Returns the {@link Revision}.
     */
    public Revision revision() {
        return revision;
    }

    /**
     * Returns the list of {@link Change}s.
     */
    public List<Change<?>> changes() {
        return changes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommitResult)) {
            return false;
        }
        final CommitResult that = (CommitResult) o;
        return Objects.equal(revision, that.revision) &&
               Objects.equal(changes, that.changes);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(revision, changes);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("revision", revision)
                          .add("changes", changes)
                          .toString();
    }
}
