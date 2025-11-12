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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.EncryptionAtRestConfig;
import com.linecorp.centraldogma.server.auth.SessionKey;
import com.linecorp.centraldogma.server.auth.SessionMasterKey;

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

        final String kekId = encryptionAtRestConfig.kekId();
        assert kekId != null;
        return new DefaultEncryptionStorageManager(rocksDbPath.toString(),
                                                   encryptionAtRestConfig.encryptSessionCookie(), kekId);
    }

    /**
     * Creates a new {@link EncryptionStorageManager} instance.
     */
    @VisibleForTesting
    static EncryptionStorageManager of(Path path, boolean encryptSessionCookie, String kekId) {
        requireNonNull(path, "path");
        return new DefaultEncryptionStorageManager(path.toString(), encryptSessionCookie, kekId);
    }

    /**
     * Returns {@code true} if the encryption at rest is enabled.
     */
    boolean enabled();

    /**
     * Returns {@code true} if the session cookie should be encrypted.
     */
    boolean encryptSessionCookie();

    /**
     * Returns the ID of the key encryption key (KEK).
     */
    String kekId();

    /**
     * Generates a new data encryption key (DEK) and wraps it. The specified key version is included in
     * the returned {@link WrappedDekDetails#dekVersion()}.
     */
    CompletableFuture<String> generateWdek();

    /**
     * Generates a new session master key.
     */
    CompletableFuture<SessionMasterKey> generateSessionMasterKey(int version);

    /**
     * Stores the session master key.
     */
    void storeSessionMasterKey(SessionMasterKey sessionMasterKey);

    /**
     * Returns the current session master key.
     */
    SessionMasterKey getCurrentSessionMasterKey();

    /**
     * Returns the current session key that is derived from the current session master key.
     */
    CompletableFuture<SessionKey> getCurrentSessionKey();

    /**
     * Returns the session key for the specified version.
     */
    CompletableFuture<SessionKey> getSessionKey(int version);

    /**
     * Rotates the session master key.
     */
    void rotateSessionMasterKey(SessionMasterKey sessionMasterKey);

    /**
     * Returns all wrapped data encryption keys (WDEKs).
     */
    List<WrappedDekDetails> wdeks();

    /**
     * Returns the data encryption key (DEK) for the specified project and repository.
     */
    SecretKey getDek(String projectName, String repoName, int version);

    /**
     * Returns the current wrapped data encryption key (WDEK) for the specified project and repository.
     */
    SecretKeyWithVersion getCurrentDek(String projectName, String repoName);

    /**
     * Stores the wrapped data encryption key (WDEK) for the specified project and repository.
     * This raises an exception if the WDEK already exists.
     */
    void storeWdek(String projectName, String repoName, WrappedDekDetails wdekDetails);

    /**
     * Rotates the wrapped data encryption key (WDEK) for the {@link WrappedDekDetails#projectName()} and
     * {@link WrappedDekDetails#repoName()}.
     */
    void rotateWdek(WrappedDekDetails wdekDetails);

    /**
     * Removes the wrapped data encryption key (WDEK) for the specified project and repository.
     */
    void removeWdek(String projectName, String repoName, int version);

    /**
     * Returns the object associated with the specified key.
     */
    @Nullable
    byte[] getObject(byte[] key, byte[] metadataKey);

    /**
     * Returns the object ID bytes associated with the specified key.
     */
    @Nullable
    byte[] getObjectId(byte[] key, byte[] metadataKey);

    /**
     * Returns the value of the specified metadata key.
     */
    @Nullable
    byte[] getMetadata(byte[] metadataKey);

    /**
     * Stores the specified key-value object with metadata.
     */
    void putObject(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value);

    /**
     * Stores the specified key-value pair with metadata. The {@code previousKeyToRemove} will be removed.
     */
    void putObjectId(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value,
                     @Nullable byte[] previousKeyToRemove);

    /**
     * Returns {@code true} if the specified key exists.
     */
    boolean containsMetadata(byte[] key);

    /**
     * Deletes the specified keys.
     */
    void deleteObjectId(byte[] metadataKey, byte[] key);

    /**
     * Deletes all data related to the specified project and repository.
     */
    void deleteRepositoryData(String projectName, String repoName);

    /**
     * Returns all data stored in the encryption storage manager.
     *
     * @deprecated Do not use this method for production code as it may return a large amount of data.
     */
    @Deprecated
    Map<String, Map<String, byte[]>> getAllData();

    /**
     * Adds a listener that is called when a new session key is stored.
     */
    void addSessionKeyListener(Consumer<SessionKey> listener);
}
