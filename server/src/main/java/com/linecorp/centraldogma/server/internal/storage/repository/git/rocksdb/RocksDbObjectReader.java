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

import java.io.IOException;
import java.util.Collection;
import java.util.Set;

import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

final class RocksDbObjectReader extends ObjectReader {

    private final EncryptionGitStorage encryptionGitStorage;

    RocksDbObjectReader(EncryptionGitStorage encryptionGitStorage) {
        this.encryptionGitStorage = encryptionGitStorage;
    }

    @Override
    public ObjectReader newReader() {
        return this;
    }

    @Override
    public Collection<ObjectId> resolve(AbbreviatedObjectId id) throws IOException {
        if (id.isComplete()) {
            final ObjectLoader loader = encryptionGitStorage.getObject(id.toObjectId(), OBJ_ANY);
            if (loader != null) {
                return ImmutableList.of(id.toObjectId());
            } else {
                return ImmutableList.of(); // doesn't exist
            }
        }
        throw new UnsupportedOperationException("Resolving abbreviated object ID is not supported");
    }

    @Override
    public ObjectLoader open(AnyObjectId objectId, int typeHint) throws IOException {
        final ObjectId toObjectId = objectId.toObjectId();
        final ObjectLoader objectLoader = encryptionGitStorage.getObject(toObjectId, typeHint);
        if (objectLoader == null) {
            if (typeHint == OBJ_ANY) {
                throw new MissingObjectException(toObjectId, JGitText.get().unknownObjectType2);
            }
            throw new MissingObjectException(toObjectId, typeHint);
        }
        return objectLoader;
    }

    @Override
    public Set<ObjectId> getShallowCommits() throws IOException {
        // The underlying EncryptionGitStorage currently has no defined mechanism
        // for storing or retrieving shallow commit information (the equivalent
        // of the .git/shallow file). Therefore, this reader cannot provide
        // that information.
        return ImmutableSet.of();
    }

    @Override
    public void close() {
        // No-op
    }
}
