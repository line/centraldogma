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

import org.eclipse.jgit.lib.ObjectDatabase;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;

final class RocksDbObjectDatabase extends ObjectDatabase {

    private final EncryptionGitStorage encryptionGitStorage;
    private final RocksDbObjectReader reader;

    RocksDbObjectDatabase(EncryptionGitStorage encryptionGitStorage) {
        this.encryptionGitStorage = encryptionGitStorage;
        reader = new RocksDbObjectReader(encryptionGitStorage);
    }

    @Override
    public ObjectInserter newInserter() {
        return new RocksDbObjectInserter(encryptionGitStorage, reader);
    }

    @Override
    public ObjectReader newReader() {
        return reader;
    }

    // JGit 6.0+ requires this method to be overridden.
    @SuppressWarnings("override")
    public long getApproximateObjectCount() {
        return -1;
    }

    @Override
    public void close() {
        // No-op
    }
}
