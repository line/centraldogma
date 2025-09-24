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
package com.linecorp.centraldogma.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

class SessionKeyTest {

    @TempDir
    private static File rootDir;

    @Test
    void test() {
        final EncryptionStorageManager encryptionStorageManager =
                EncryptionStorageManager.of(new File(rootDir, "rocksdb").toPath(), true);
        final SessionMasterKey sessionMasterKey = encryptionStorageManager.generateSessionMasterKey().join();
        assertThat(sessionMasterKey.salt().length).isEqualTo(32);

        encryptionStorageManager.storeSessionMasterKey(sessionMasterKey);
        final SessionKey sessionKey = encryptionStorageManager.getCurrentSessionKey().join();
        final byte[] encryptionKey = sessionKey.encryptionKey().getEncoded();
        assertThat(encryptionKey.length).isEqualTo(32);
        final byte[] signingKey = sessionKey.signingKey().getEncoded();
        assertThat(signingKey.length).isEqualTo(32);

        // It generates the same session key with the same master key and salt.
        final SessionKey sessionKey2 = encryptionStorageManager.getCurrentSessionKey().join();
        assertThat(sessionKey2.signingKey().getEncoded()).isEqualTo(signingKey);
        assertThat(sessionKey2.encryptionKey().getEncoded()).isEqualTo(encryptionKey);

        encryptionStorageManager.close();
    }
}
