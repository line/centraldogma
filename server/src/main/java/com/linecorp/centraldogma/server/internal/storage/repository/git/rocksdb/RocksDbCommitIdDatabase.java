/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.git.CommitIdDatabase;

public final class RocksDbCommitIdDatabase implements CommitIdDatabase {

    private final EncryptionGitStorage storage;
    @Nullable
    private volatile Revision headRevision;

    public RocksDbCommitIdDatabase(EncryptionGitStorage storage, @Nullable Revision headRevision) {
        this.storage = requireNonNull(storage, "storage");
        this.headRevision = headRevision;
    }

    @Nullable
    @Override
    public Revision headRevision() {
        return headRevision;
    }

    @Override
    public ObjectId get(Revision revision) {
        // TODO(minwoox): Dedup
        final Revision headRevision = this.headRevision;
        checkState(headRevision != null, "initial commit not available yet: %s/%s",
                   storage.projectName(), storage.repoName());
        checkArgument(!revision.isRelative(), "revision: %s (expected: an absolute revision)", revision);
        if (revision.major() > headRevision.major()) {
            throw new RevisionNotFoundException(revision);
        }

        return storage.getRevisionObjectId(revision);
    }

    @Override
    public void put(Revision revision, ObjectId commitId) {
        // TODO(minwoox): Dedup
        final Revision expected;
        final Revision headRevision = this.headRevision;
        if (headRevision == null) {
            expected = Revision.INIT;
        } else {
            expected = headRevision.forward(1);
        }
        checkState(revision.equals(expected), "incorrect revision: %s (expected: %s)", revision, expected);

        storage.putRevisionObjectId(revision, commitId);
        if (headRevision == null ||
            headRevision.major() < revision.major()) {
            this.headRevision = revision;
        }
    }

    @Override
    public void rebuild(Repository gitRepo) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws Exception {
        // No-op
    }
}
