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
package com.linecorp.centraldogma.server.storage.encryption;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

/**
 * A no-operation implementation of {@link EncryptionStorageManager} that does not perform any encryption.
 */
public enum NoopEncryptionStorageManager implements EncryptionStorageManager {

    /**
     * Singleton instance of {@link NoopEncryptionStorageManager}.
     */
    INSTANCE;

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public CompletableFuture<byte[]> generateWdek() {
        throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public SecretKey getDek(String projectName, String repoName) {
        return null;
    }

    @Override
    public void storeWdek(String projectName, String repoName, byte[] wdek) {
        // No-op
    }

    @Override
    public void removeWdek(String projectName, String repoName) {
        // No-op
    }

    @Nullable
    @Override
    public byte[] get(byte[] key) {
        return null;
    }

    @Override
    public byte[] getMetadata(byte[] metadataKey) {
        return null;
    }

    @Override
    public void put(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value) {
        // No-op
    }

    @Override
    public void putAndRemovePrevious(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value,
                                     byte[] previousKeyToRemove) {
        // No-op
    }

    @Override
    public boolean containsMetadata(byte[] key) {
        return false;
    }

    @Override
    public void delete(byte[] metadataKey, byte[] key) {
        // No-op
    }

    @Override
    public void deleteRepositoryData(String projectName, String repoName) {
        // No-op
    }

    @Override
    public void close() {
        // No-op
    }
}
