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
import java.io.InputStream;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.transport.PackParser;
import org.eclipse.jgit.util.IO;

final class RocksDbObjectInserter extends ObjectInserter {

    private final EncryptionGitStorage encryptionGitStorage;
    private final RocksDbObjectReader reader;

    RocksDbObjectInserter(EncryptionGitStorage encryptionGitStorage, RocksDbObjectReader reader) {
        this.encryptionGitStorage = encryptionGitStorage;
        this.reader = reader;
    }

    @Override
    public ObjectId insert(int type, byte[] data, int off, int len) throws IOException {
        final ObjectId id = idFor(type, data, off, len);
        return encryptionGitStorage.insertObject(id, type, data, off, len);
    }

    @Override
    public ObjectId insert(int type, long length, InputStream in) throws IOException {
        final byte[] buf;
        if (length <= buffer().length) {
            buf = buffer();
        } else {
            buf = new byte[(int) length];
        }
        final int actLen = IO.readFully(in, buf, 0);
        return insert(type, buf, 0, actLen);
    }

    @Override
    public PackParser newPackParser(InputStream in) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ObjectReader newReader() {
        return reader;
    }

    @Override
    public void flush() throws IOException {
        // No-op
    }

    @Override
    public void close() {
        // No-op
    }
}
