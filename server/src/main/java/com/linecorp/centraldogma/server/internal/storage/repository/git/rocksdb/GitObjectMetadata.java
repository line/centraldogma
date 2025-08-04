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
import static com.linecorp.centraldogma.server.internal.storage.encryption.EncryptionUtil.getInt;
import static com.linecorp.centraldogma.server.internal.storage.encryption.EncryptionUtil.putInt;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher;

public final class GitObjectMetadata {

    public static GitObjectMetadata of(int keyVersion, byte[] nonce, int type, byte[] objectWdek) {
        return new GitObjectMetadata(keyVersion, nonce, type, objectWdek);
    }

    public static GitObjectMetadata fromBytes(byte[] metadata) {
        // metadata: key version(4) + Nonce(12) + type(4) + objectWdek(48)
        final int expectedLength = 4 + NONCE_SIZE_BYTES + 4 + KEY_SIZE_BYTES + 16;
        if (metadata.length != expectedLength) {
            throw new IllegalArgumentException("Invalid metadata length: expected " + expectedLength +
                                               ", got " + metadata.length);
        }
        final byte[] nonce = new byte[NONCE_SIZE_BYTES];

        System.arraycopy(metadata, 4, nonce, 0, NONCE_SIZE_BYTES);
        final int wdekLength = metadata.length - (4 + NONCE_SIZE_BYTES + 4); // 48
        final byte[] objectWdek = new byte[wdekLength];
        System.arraycopy(metadata, 4 + NONCE_SIZE_BYTES + 4, objectWdek, 0, wdekLength);

        return new GitObjectMetadata(getInt(metadata, 0),
                                     nonce,
                                     getInt(metadata, 4 + NONCE_SIZE_BYTES),
                                     objectWdek);
    }

    private final int keyVersion;
    private final byte[] nonce;
    private final int type;
    private final byte[] objectWdek;

    private GitObjectMetadata(int keyVersion, byte[] nonce, int type, byte[] objectWdek) {
        this.keyVersion = keyVersion;
        this.nonce = nonce;
        this.type = type;
        this.objectWdek = objectWdek;
    }

    public int keyVersion() {
        return keyVersion;
    }

    public byte[] nonce() {
        return nonce;
    }

    public int type() {
        return type;
    }

    public byte[] objectWdek() {
        return objectWdek;
    }

    public byte[] toBytes() {
        // key version(4) + Nonce(12) + type(4) + objectWdek(48)
        final byte[] bytes = new byte[4 + NONCE_SIZE_BYTES + 4 + objectWdek.length];
        int index = 0;
        putInt(bytes, index, keyVersion);
        index += 4;
        System.arraycopy(nonce, 0, bytes, index, NONCE_SIZE_BYTES);
        index += NONCE_SIZE_BYTES;
        putInt(bytes, index, type);
        index += 4;
        System.arraycopy(objectWdek, 0, bytes, index, objectWdek.length);
        return bytes;
    }

    public SecretKeySpec objectDek(SecretKey dek) throws Exception {
        final byte[] objectDekRaw = AesGcmSivCipher.decrypt(dek, nonce, objectWdek);
        return aesSecretKey(objectDekRaw);
    }
}
