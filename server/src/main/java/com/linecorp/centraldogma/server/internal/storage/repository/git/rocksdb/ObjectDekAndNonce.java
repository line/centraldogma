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

import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.KEY_SIZE_BYTES;
import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.NONCE_SIZE_BYTES;
import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.aesSecretKey;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher;

public final class ObjectDekAndNonce {

    public static ObjectDekAndNonce extract(byte[] metadata, SecretKey dek) throws Exception {
        // metadata: key version(4) + Nonce(12) + type(4) + objectWdek(48)
        if (metadata.length != 4 + NONCE_SIZE_BYTES + 4 + KEY_SIZE_BYTES + 16) {
            throw new IllegalArgumentException("Invalid metadata length: expected " +
                                               (4 + NONCE_SIZE_BYTES + 4 + KEY_SIZE_BYTES + 16) +
                                               ", got " + metadata.length);
        }
        final byte[] nonce = new byte[NONCE_SIZE_BYTES];

        System.arraycopy(metadata, 4, nonce, 0, NONCE_SIZE_BYTES);
        final int wdekLength = metadata.length - (4 + NONCE_SIZE_BYTES + 4); // 48
        final byte[] objectWdek = new byte[wdekLength];
        System.arraycopy(metadata, 4 + NONCE_SIZE_BYTES + 4, objectWdek, 0, wdekLength);

        final byte[] objectDekRaw = AesGcmSivCipher.decrypt(dek, nonce, objectWdek);
        final SecretKeySpec objectDek = aesSecretKey(objectDekRaw);
        return new ObjectDekAndNonce(objectDek, nonce);
    }

    private final SecretKeySpec objectDek;
    private final byte[] nonce;

    private ObjectDekAndNonce(SecretKeySpec objectDek, byte[] nonce) {
        this.objectDek = objectDek;
        this.nonce = nonce;
    }

    public SecretKeySpec objectDek() {
        return objectDek;
    }

    public byte[] nonce() {
        return nonce;
    }
}
