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

import static com.linecorp.centraldogma.server.storage.encryption.DefaultEncryptionStorageManager.ROCKSDB_PATH;
import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.EncryptionAtRestConfig;

/**
 * Manages the storage of encrypted data at rest.
 */
public interface EncryptionStorageManager extends SafeCloseable {

    /**
     * Creates a new {@link EncryptionStorageManager} instance.
     */
    static EncryptionStorageManager of(CentralDogmaConfig cfg) {
        requireNonNull(cfg, "cfg");
        final EncryptionAtRestConfig encryptionAtRestConfig = cfg.encryptionAtRest();
        final boolean enabled = encryptionAtRestConfig != null && encryptionAtRestConfig.enabled();
        final Path rocksDbPath = cfg.dataDir().toPath().resolve(ROCKSDB_PATH);
        if (!enabled) {
            if (rocksDbPath.toFile().exists()) {
                throw new IllegalArgumentException("RocksDB path exists but encryption at rest is disabled.");
            }
            return NoopEncryptionStorageManager.INSTANCE;
        }

        return new DefaultEncryptionStorageManager(rocksDbPath.toString());
    }

    /**
     * Creates a new {@link EncryptionStorageManager} instance.
     */
    @VisibleForTesting
    static EncryptionStorageManager of(Path path) {
        requireNonNull(path, "path");
        return new DefaultEncryptionStorageManager(path.toString());
    }

    /**
     * Returns {@code true} if the encryption at rest is enabled.
     */
    boolean enabled();

    /**
     * Generates a new data encryption key (DEK) and wraps it.
     */
    CompletableFuture<byte[]> generateWdek();

    /**
     * Returns the wrapped data encryption key (WDEK) for the specified project and repository.
     */
    SecretKey getDek(String projectName, String repoName);

    /**
     * Stores the wrapped data encryption key (WDEK) for the specified project and repository.
     * This raises an exception if the WDEK already exists.
     */
    void storeWdek(String projectName, String repoName, byte[] wdek);

    /**
     * Removes the wrapped data encryption key (WDEK) for the specified project and repository.
     */
    void removeWdek(String projectName, String repoName);

    /**
     * Returns the value of the specified key.
     */
    @Nullable
    byte[] get(byte[] key);

    /**
     * Returns the value of the specified metadata key.
     */
    @Nullable
    byte[] getMetadata(byte[] metadataKey);

    /**
     * Stores the specified key-value pair with metadata.
     */
    void put(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value);

    /**
     * Returns {@code true} if the specified key exists.
     */
    boolean containsMetadata(byte[] key);

    /**
     * Removes the specified keys.
     */
    void delete(byte[] metadataKey, byte[] key);
}
