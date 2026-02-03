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
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.crypto.SecretKey;

import org.jspecify.annotations.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.EncryptionConfig;
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
        final EncryptionConfig encryptionConfig = cfg.encryption();
        final boolean enabled = encryptionConfig != null && encryptionConfig.enabled();
        final Path rocksDbPath = cfg.dataDir().toPath().resolve(ROCKSDB_PATH);
        if (!enabled) {
            if (rocksDbPath.toFile().exists()) {
                throw new IllegalArgumentException("RocksDB path exists but encryption is disabled.");
            }
            return NoopEncryptionStorageManager.INSTANCE;
        }

        final String kekId = encryptionConfig.kekId();
        assert kekId != null;
        return new DefaultEncryptionStorageManager(rocksDbPath.toString(),
                                                   encryptionConfig.encryptSessionCookie(), kekId);
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
     * Returns {@code true} if the encryption is enabled.
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
     * Generates a new data encryption key (DEK) and wraps it.
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
     * Stores the wrapped data encryption key (WDEK) for the {@link WrappedDekDetails#projectName()} and
     * {@link WrappedDekDetails#repoName()}.
     * This raises an exception if the WDEK already exists.
     */
    void storeWdek(WrappedDekDetails wdekDetails);

    /**
     * Rotates the wrapped data encryption key (WDEK) for the {@link WrappedDekDetails#projectName()} and
     * {@link WrappedDekDetails#repoName()}.
     */
    void rotateWdek(WrappedDekDetails wdekDetails);

    /**
     * Removes the wrapped data encryption key (WDEK) for the specified project and repository.
     */
    void removeWdek(String projectName, String repoName, int version, boolean removeCurrent);

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
                     byte @Nullable [] previousKeyToRemove);

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
     * Re-encrypts all data for the specified repository with the current DEK version.
     * This is typically called after a DEK rotation to ensure all data is encrypted
     * with the latest key version.
     */
    void reencryptRepositoryData(String projectName, String repoName);

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

    /**
     * Adds a listener that is called when the current DEK for a repository is updated or removed.
     * The listener receives the project/repo key and the new DEK (or null if removed).
     */
    void addCurrentDekListener(String projectName, String repoName,
                               Consumer<SecretKeyWithVersion> listener);

    /**
     * Removes a previously registered current DEK listener.
     */
    void removeCurrentDekListener(String projectName, String repoName);

    /**
     * Rewraps all wrapped data encryption keys (WDEKs) and session master keys
     * with the {@link EncryptionConfig#kekId()} specified in the configuration.
     *
     * @param executor the {@link Executor} to use for storing re-wrapped keys.
     */
    CompletableFuture<Void> rewrapAllKeys(Executor executor);
}
