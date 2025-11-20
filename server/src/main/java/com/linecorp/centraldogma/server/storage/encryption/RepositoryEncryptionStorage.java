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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.NONCE_SIZE_BYTES;
import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.aesSecretKey;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage.OBJS;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage.REFS;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage.REV2SHA;
import static com.linecorp.centraldogma.server.storage.encryption.RocksDBStorage.ENCRYPTED_OBJECT_COLUMN_FAMILY;
import static com.linecorp.centraldogma.server.storage.encryption.RocksDBStorage.ENCRYPTED_OBJECT_ID_COLUMN_FAMILY;
import static com.linecorp.centraldogma.server.storage.encryption.RocksDBStorage.ENCRYPTION_METADATA_COLUMN_FAMILY;
import static com.linecorp.centraldogma.server.storage.encryption.RocksDBStorage.WDEK_COLUMN_FAMILY;
import static java.util.Objects.requireNonNull;
import static org.eclipse.jgit.lib.Constants.HEAD;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.GitObjectMetadata;

final class RepositoryEncryptionStorage {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryEncryptionStorage.class);
    private static final int BATCH_WRITE_SIZE = 1000;

    private final RocksDBStorage rocksDbStorage;
    private final KeyWrapper keyWrapper;
    private final String kekId;

    private final ReentrantShortLock lock = new ReentrantShortLock();
    @GuardedBy("lock")
    private final Map<String, Consumer<SecretKeyWithVersion>> currentDekListeners = new HashMap<>();

    RepositoryEncryptionStorage(RocksDBStorage rocksDbStorage, KeyWrapper keyWrapper, String kekId) {
        this.rocksDbStorage = requireNonNull(rocksDbStorage, "rocksDbStorage");
        this.keyWrapper = requireNonNull(keyWrapper, "keyWrapper");
        this.kekId = requireNonNull(kekId, "kekId");
    }

    CompletableFuture<String> generateWdek() {
        final byte[] dek = AesGcmSivCipher.generateAes256Key();
        return keyWrapper.wrap(dek, kekId);
    }

    List<WrappedDekDetails> wdeks() {
        final ColumnFamilyHandle wdekCf = rocksDbStorage.getColumnFamilyHandle(WDEK_COLUMN_FAMILY);
        try (RocksIterator iterator = rocksDbStorage.newIterator(wdekCf)) {
            iterator.seekToFirst();
            final ImmutableList.Builder<WrappedDekDetails> wdekDetailsBuilder = ImmutableList.builder();
            while (iterator.isValid()) {
                final String key = new String(iterator.key(), StandardCharsets.UTF_8);
                if (key.startsWith("wdeks/") && !key.endsWith("/current")) {
                    try {
                        final WrappedDekDetails wdekDetails =
                                Jackson.readValue(iterator.value(), WrappedDekDetails.class);
                        wdekDetailsBuilder.add(wdekDetails);
                    } catch (JsonParseException | JsonMappingException e) {
                        logger.warn("Failed to read WDEK for key: {}", key, e);
                    }
                }
                iterator.next();
            }
            return wdekDetailsBuilder.build();
        }
    }

    SecretKey getDek(String projectName, String repoName, int version) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        return getDek0(projectName, repoName, version);
    }

    private SecretKeySpec getDek0(String projectName, String repoName, int version) {
        final byte[] wdekDetailsBytes;
        try {
            wdekDetailsBytes = rocksDbStorage.get(WDEK_COLUMN_FAMILY,
                                                  repoWdekDbKey(projectName, repoName, version));
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to get WDEK of " + projectRepoVersion(projectName, repoName, version), e);
        }

        if (wdekDetailsBytes == null) {
            throw new EncryptionStorageException(
                    "WDEK of " + projectRepoVersion(projectName, repoName, version) + " does not exist");
        }

        final WrappedDekDetails wdekDetails;
        try {
            wdekDetails = Jackson.readValue(wdekDetailsBytes, WrappedDekDetails.class);
        } catch (JsonParseException | JsonMappingException e) {
            throw new EncryptionStorageException(
                    "Failed to read WDEK of " + projectRepoVersion(projectName, repoName, version), e);
        }

        return blockingUnwrap(projectName, repoName, version, wdekDetails);
    }

    private SecretKeySpec blockingUnwrap(String projectName, String repoName,
                                         int version, WrappedDekDetails wdekDetails) {
        final byte[] key;
        try {
            key = keyWrapper.unwrap(wdekDetails.wrappedDek(), wdekDetails.kekId()).get(10, TimeUnit.SECONDS);
        } catch (Throwable t) {
            throw new EncryptionStorageException(
                    "Failed to unwrap WDEK of " + projectRepoVersion(projectName, repoName, version), t);
        }
        return aesSecretKey(key);
    }

    SecretKeyWithVersion getCurrentDek(String projectName, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        return getCurrentDek0(projectName, repoName);
    }

    private SecretKeyWithVersion getCurrentDek0(String projectName, String repoName) {
        final int version;
        try {
            final byte[] versionBytes = rocksDbStorage.get(WDEK_COLUMN_FAMILY,
                                                           repoCurrentWdekDbKey(projectName, repoName));
            if (versionBytes == null) {
                throw new EncryptionStorageException(
                        "Current WDEK of " + projectRepo(projectName, repoName) + " does not exist");
            }
            version = Ints.fromByteArray(versionBytes);
        } catch (IllegalArgumentException e) {
            throw new EncryptionStorageException(
                    "Failed to parse the current WDEK version of " + projectRepo(projectName, repoName), e);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to get the current WDEK of " + projectRepo(projectName, repoName), e);
        }

        return new SecretKeyWithVersion(getDek(projectName, repoName, version), version);
    }

    void storeWdek(WrappedDekDetails wdekDetails) {
        storeWdek(wdekDetails, false);
    }

    private void storeWdek(WrappedDekDetails wdekDetails, boolean rotate) {
        requireNonNull(wdekDetails, "wdekDetails");
        final String projectName = wdekDetails.projectName();
        final String repoName = wdekDetails.repoName();
        final int version = wdekDetails.dekVersion();
        if (rotate) {
            final SecretKeyWithVersion currentDek = getCurrentDek(projectName, repoName);
            if (wdekDetails.dekVersion() != currentDek.version() + 1) {
                throw new EncryptionStorageException(
                        "The WDEK version to rotate must be exactly one greater than the " +
                        "current version. " + "Current version: " + currentDek.version() +
                        ", rotated version: " + wdekDetails.dekVersion());
            }
        }

        final byte[] wdekKeyBytes = repoWdekDbKey(projectName, repoName, version);
        try {
            final byte[] existingWdekBytes = rocksDbStorage.get(WDEK_COLUMN_FAMILY, wdekKeyBytes);
            if (existingWdekBytes != null) {
                throw new EncryptionEntryExistsException(
                        "WDEK of " + projectRepoVersion(projectName, repoName, version) +
                        " already exists");
            }
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to check the existence of WDEK for " +
                                                 projectRepoVersion(projectName, repoName, version), e);
        }

        final SecretKeySpec unwrap = blockingUnwrap(projectName, repoName, version, wdekDetails);

        final byte[] wdekBytes;
        try {
            wdekBytes = Jackson.writeValueAsBytes(wdekDetails);
        } catch (JsonProcessingException e) {
            throw new EncryptionStorageException(
                    "Failed to serialize WDEK of " + projectRepoVersion(projectName, repoName, version), e);
        }

        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            final ColumnFamilyHandle wdekCf = rocksDbStorage.getColumnFamilyHandle(WDEK_COLUMN_FAMILY);
            writeBatch.put(wdekCf, wdekKeyBytes, wdekBytes);
            writeBatch.put(wdekCf, repoCurrentWdekDbKey(projectName, repoName), Ints.toByteArray(version));
            rocksDbStorage.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to store WDEK of " + projectRepoVersion(projectName, repoName, version), e);
        }
        notifyCurrentDekListener(projectName, repoName, new SecretKeyWithVersion(unwrap, version));
    }

    void rotateWdek(WrappedDekDetails wdekDetails) {
        requireNonNull(wdekDetails, "wdekDetails");
        storeWdek(wdekDetails, true);
    }

    void removeWdek(String projectName, String repoName, int version, boolean removeCurrent) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        try {
            try (WriteBatch writeBatch = new WriteBatch();
                 WriteOptions writeOptions = new WriteOptions()) {
                writeOptions.setSync(true);
                final ColumnFamilyHandle wdekCf = rocksDbStorage.getColumnFamilyHandle(WDEK_COLUMN_FAMILY);
                writeBatch.delete(wdekCf, repoWdekDbKey(projectName, repoName, version));
                if (removeCurrent) {
                    writeBatch.delete(wdekCf, repoCurrentWdekDbKey(projectName, repoName));
                }
                rocksDbStorage.write(writeOptions, writeBatch);
            }
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to remove WDEK of " + projectRepoVersion(projectName, repoName, version), e);
        }
        if (removeCurrent) {
            removeCurrentDekListener(projectName, repoName);
        }
    }

    @Nullable
    byte[] getObject(byte[] key, byte[] metadataKey) {
        requireNonNull(key, "key");
        requireNonNull(metadataKey, "metadataKey");
        try {
            return rocksDbStorage.get(ENCRYPTED_OBJECT_COLUMN_FAMILY, key);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to get object. metadata key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Nullable
    byte[] getObjectId(byte[] key, byte[] metadataKey) {
        requireNonNull(key, "key");
        requireNonNull(metadataKey, "metadataKey");
        try {
            return rocksDbStorage.get(ENCRYPTED_OBJECT_ID_COLUMN_FAMILY, key);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to get object ID. metadata key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Nullable
    byte[] getMetadata(byte[] metadataKey) {
        requireNonNull(metadataKey, "metadataKey");
        try {
            return rocksDbStorage.get(ENCRYPTION_METADATA_COLUMN_FAMILY, metadataKey);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to get metadata. key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    void putObject(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value) {
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            writeBatch.put(rocksDbStorage.getColumnFamilyHandle(ENCRYPTION_METADATA_COLUMN_FAMILY),
                           metadataKey, metadataValue);
            writeBatch.put(rocksDbStorage.getColumnFamilyHandle(ENCRYPTED_OBJECT_COLUMN_FAMILY),
                           key, value);
            rocksDbStorage.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to write object key-value with metadata. metadata key: " +
                    new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    void putObjectId(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value,
                     @Nullable byte[] previousKeyToRemove) {
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            writeBatch.put(rocksDbStorage.getColumnFamilyHandle(ENCRYPTION_METADATA_COLUMN_FAMILY),
                           metadataKey, metadataValue);
            final ColumnFamilyHandle objectIdcolumnFamilyHandle =
                    rocksDbStorage.getColumnFamilyHandle(ENCRYPTED_OBJECT_ID_COLUMN_FAMILY);
            writeBatch.put(objectIdcolumnFamilyHandle, key, value);
            if (previousKeyToRemove != null) {
                writeBatch.delete(objectIdcolumnFamilyHandle, previousKeyToRemove);
            }
            rocksDbStorage.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to write object key-value with metadata. metadata key: " +
                    new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    boolean containsMetadata(byte[] key) {
        requireNonNull(key, "key");
        try {
            final byte[] value = rocksDbStorage.get(ENCRYPTION_METADATA_COLUMN_FAMILY, key);
            return value != null;
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to check existence of metadata. key: " +
                                                 new String(key, StandardCharsets.UTF_8), e);
        }
    }

    void deleteObjectId(byte[] metadataKey, byte[] key) {
        requireNonNull(metadataKey, "metadataKey");
        requireNonNull(key, "key");
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            writeBatch.delete(rocksDbStorage.getColumnFamilyHandle(ENCRYPTION_METADATA_COLUMN_FAMILY),
                              metadataKey);
            writeBatch.delete(rocksDbStorage.getColumnFamilyHandle(ENCRYPTED_OBJECT_ID_COLUMN_FAMILY), key);
            rocksDbStorage.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to delete object ID key-value with metadata. metadata key: " +
                    new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    void deleteRepositoryData(String projectName, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        logger.info("Deleting encrypted data for repository: {}/{}", projectName, repoName);

        final String projectRepoPrefix = projectRepo(projectName, repoName) + '/';
        final byte[] projectRepoPrefixBytes = projectRepoPrefix.getBytes(StandardCharsets.UTF_8);

        final byte[] objectKeyPrefixBytes = (projectRepoPrefix + OBJS).getBytes(StandardCharsets.UTF_8);
        final byte[] refsKeyPrefixBytes = (projectRepoPrefix + REFS).getBytes(StandardCharsets.UTF_8);
        final byte[] headKeyBytes = (projectRepoPrefix + HEAD).getBytes(StandardCharsets.UTF_8);
        final byte[] rev2ShaPrefixBytes = (projectRepoPrefix + REV2SHA).getBytes(StandardCharsets.UTF_8);

        int totalDeletedCount = 0;
        int operationsInCurrentBatch = 0;

        final ColumnFamilyHandle metadataColumnFamilyHandle =
                rocksDbStorage.getColumnFamilyHandle(ENCRYPTION_METADATA_COLUMN_FAMILY);
        final ColumnFamilyHandle encryptedObjectHandle =
                rocksDbStorage.getColumnFamilyHandle(ENCRYPTED_OBJECT_COLUMN_FAMILY);
        final ColumnFamilyHandle encryptedObjectIdHandle =
                rocksDbStorage.getColumnFamilyHandle(ENCRYPTED_OBJECT_ID_COLUMN_FAMILY);
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions();
             RocksIterator iterator = rocksDbStorage.newIterator(metadataColumnFamilyHandle)) {

            iterator.seek(projectRepoPrefixBytes);
            while (iterator.isValid()) {
                final byte[] metadataKey = iterator.key();

                if (!startsWith(metadataKey, projectRepoPrefixBytes)) {
                    break;
                }

                final byte[] metadataValue = iterator.value();
                final byte[] idPart; // ObjectId, refName or revNum

                if (startsWith(metadataKey, objectKeyPrefixBytes)) {
                    // MetadataKey: project/repo/objs/<objectId_bytes(20)>
                    // MetadataValue: key version(4) + nonce(12) + type(4) + objectWdek(48)
                    if (metadataKey.length == objectKeyPrefixBytes.length + 20) {
                        idPart = Arrays.copyOfRange(metadataKey, objectKeyPrefixBytes.length,
                                                    metadataKey.length);
                        if (metadataValue != null) {
                            final GitObjectMetadata gitObjectMetadata =
                                    GitObjectMetadata.fromBytes(metadataValue);

                            final SecretKeySpec objectDek;
                            try {
                                objectDek = gitObjectMetadata.objectDek(
                                        getDek(projectName, repoName, gitObjectMetadata.keyVersion()));
                            } catch (Exception e) {
                                throw new EncryptionStorageException(
                                        "Failed to get object dek for " +
                                        new String(metadataKey, StandardCharsets.UTF_8), e);
                            }

                            final byte[] key = AesGcmSivCipher.encrypt(objectDek,
                                                                       gitObjectMetadata.nonce(), idPart);
                            writeBatch.delete(encryptedObjectHandle, key);
                            operationsInCurrentBatch++;
                            totalDeletedCount++;
                        } else {
                            logger.warn("Invalid metadata value for object key: {}",
                                        new String(metadataKey, StandardCharsets.UTF_8));
                        }
                    } else {
                        logger.warn("Invalid object metadata key length: {}",
                                    new String(metadataKey, StandardCharsets.UTF_8));
                    }
                } else {
                    if (startsWith(metadataKey, rev2ShaPrefixBytes)) {
                        // MetadataKey: project/repo/rev2sha/<revision_major_bytes(4)>
                        // MetadataValue: key version(4) + nonce(12)
                        if (metadataKey.length == rev2ShaPrefixBytes.length + 4) {
                            idPart = Arrays.copyOfRange(metadataKey, rev2ShaPrefixBytes.length,
                                                        metadataKey.length);
                            if (metadataValue != null && metadataValue.length == 4 + NONCE_SIZE_BYTES) {
                                final byte[] nonce = new byte[NONCE_SIZE_BYTES];
                                System.arraycopy(metadataValue, 4, nonce, 0, NONCE_SIZE_BYTES);
                                final SecretKey dek = getDek(projectName, repoName,
                                                             Ints.fromByteArray(metadataValue));
                                final byte[] key = AesGcmSivCipher.encrypt(dek, nonce, idPart);
                                writeBatch.delete(encryptedObjectIdHandle, key);
                                operationsInCurrentBatch++;
                                totalDeletedCount++;
                            } else {
                                logger.warn("Invalid nonce (metadata value) for rev2sha key: {}",
                                            new String(metadataKey, StandardCharsets.UTF_8));
                            }
                        } else {
                            logger.warn("Invalid rev2sha metadata key length: {}",
                                        new String(metadataKey, StandardCharsets.UTF_8));
                        }
                    } else if (startsWith(metadataKey, headKeyBytes) ||
                               startsWith(metadataKey, refsKeyPrefixBytes)) {
                        // MetadataKey: project/repo/HEAD or
                        // MetadataKey: project/repo/refs/heads/master
                        // idPart should be "HEAD" or "refs/..." so copy from projectRepoPrefixBytes.length
                        // MetadataValue: key version(4) + nonce(12)
                        idPart = Arrays.copyOfRange(metadataKey,
                                                    projectRepoPrefixBytes.length, metadataKey.length);
                        if (metadataValue != null && metadataValue.length == 4 + NONCE_SIZE_BYTES) {
                            final byte[] nonce = new byte[NONCE_SIZE_BYTES];
                            System.arraycopy(metadataValue, 4, nonce, 0, NONCE_SIZE_BYTES);
                            final SecretKey dek = getDek(projectName, repoName,
                                                         Ints.fromByteArray(metadataValue));
                            final byte[] key = AesGcmSivCipher.encrypt(dek, nonce, idPart);
                            writeBatch.delete(encryptedObjectIdHandle, key);
                            operationsInCurrentBatch++;
                            totalDeletedCount++;
                        } else {
                            logger.warn("Invalid nonce (metadata value) for ref key: {}",
                                        new String(metadataKey, StandardCharsets.UTF_8));
                        }
                    } else {
                        logger.warn("Unknown metadata key pattern for prefix {}: {}",
                                    projectRepoPrefix, new String(metadataKey, StandardCharsets.UTF_8));
                    }
                }

                writeBatch.delete(metadataColumnFamilyHandle, metadataKey);
                operationsInCurrentBatch++;
                totalDeletedCount++;
                if (operationsInCurrentBatch >= BATCH_WRITE_SIZE) {
                    if (writeBatch.count() > 0) {
                        writeOptions.setSync(true);
                        rocksDbStorage.write(writeOptions, writeBatch);
                        logger.info("Deleted {} entries for repository {}/{}. " +
                                    "Total entries processed for deletion so far: {}.",
                                    operationsInCurrentBatch, projectName, repoName, totalDeletedCount);
                        writeBatch.clear();
                        operationsInCurrentBatch = 0;
                    }
                }

                iterator.next();
            }

            if (operationsInCurrentBatch > 0) {
                writeOptions.setSync(true);
                rocksDbStorage.write(writeOptions, writeBatch);
                logger.info("Deleted {} entries for repository {}/{}. " +
                            "Total entries processed for deletion so far: {}.",
                            operationsInCurrentBatch, projectName, repoName, totalDeletedCount);
            }

            if (totalDeletedCount > 0) {
                logger.info("Successfully deleted a total of {} entries for repository {}/{}",
                            totalDeletedCount, projectName, repoName);
            } else {
                logger.info("No data found for repository {}/{}", projectName, repoName);
            }
        } catch (RocksDBException | EncryptionStorageException e) {
            throw new EncryptionStorageException(
                    "Failed to delete repository data for " + projectRepo(projectName, repoName), e);
        } catch (Exception e) {
            throw new EncryptionStorageException("Unexpected error during repository data deletion for " +
                                                 projectRepo(projectName, repoName), e);
        }

        logger.info("Removing WDEKs for repository: {}/{}", projectName, repoName);

        final ColumnFamilyHandle wdekColumnFamilyHandle =
                rocksDbStorage.getColumnFamilyHandle(WDEK_COLUMN_FAMILY);
        final byte[] wdekPrefixBytes =
                ("wdeks/" + projectName + '/' + repoName + '/').getBytes(StandardCharsets.UTF_8);
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions();
             RocksIterator iterator = rocksDbStorage.newIterator(wdekColumnFamilyHandle)) {

            iterator.seek(wdekPrefixBytes);
            while (iterator.isValid()) {
                final byte[] wdekKey = iterator.key();

                if (!startsWith(wdekKey, wdekPrefixBytes)) {
                    break;
                }

                writeBatch.delete(wdekColumnFamilyHandle, wdekKey);
                iterator.next();
            }

            writeOptions.setSync(true);
            rocksDbStorage.write(writeOptions, writeBatch);
            logger.info("Deleted WDEKs for repository {}/{}", projectName, repoName);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to remove WDEKs for repository " + projectRepo(projectName, repoName), e);
        }
        removeCurrentDekListener(projectName, repoName);
    }

    void addCurrentDekListener(String projectName, String repoName,
                               Consumer<SecretKeyWithVersion> listener) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(listener, "listener");
        final String projectRepoKey = projectRepo(projectName, repoName);
        lock.lock();
        try {
            if (currentDekListeners.containsKey(projectRepoKey)) {
                throw new IllegalStateException(
                        "A current DEK listener is already registered for " + projectRepoKey);
            }

            currentDekListeners.put(projectRepoKey, listener);
            final SecretKeyWithVersion currentDek = getCurrentDek0(projectName, repoName);
            listener.accept(currentDek);
        } finally {
            lock.unlock();
        }
    }

    void removeCurrentDekListener(String projectName, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        final String projectRepoKey = projectRepo(projectName, repoName);
        lock.lock();
        try {
            currentDekListeners.remove(projectRepoKey);
        } finally {
            lock.unlock();
        }
    }

    private void notifyCurrentDekListener(String projectName, String repoName, SecretKeyWithVersion newDek) {
        final String projectRepoKey = projectRepo(projectName, repoName);
        lock.lock();
        try {
            final Consumer<SecretKeyWithVersion> listener = currentDekListeners.get(projectRepoKey);
            // listener can be null because storeWdek() is called before a repository is created.
            if (listener != null) {
                listener.accept(newDek);
            }
        } finally {
            lock.unlock();
        }
    }

    private static String projectRepo(String projectName, String repoName) {
        return projectName + '/' + repoName;
    }

    private static String projectRepoVersion(String projectName, String repoName, int version) {
        return projectName + '/' + repoName + '/' + version;
    }

    private static byte[] repoWdekDbKey(String projectName, String repoName, int version) {
        return ("wdeks/" + projectName + '/' + repoName + '/' + version).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repoCurrentWdekDbKey(String projectName, String repoName) {
        return ("wdeks/" + projectName + '/' + repoName + "/current").getBytes(StandardCharsets.UTF_8);
    }

    CompletableFuture<Void> rewrapAllWdeks() {
        final List<WrappedDekDetails> allWdeks = wdeks();
        if (allWdeks.isEmpty()) {
            logger.info("No WDEKs to rewrap");
            return CompletableFuture.completedFuture(null);
        }

        logger.info("Starting to rewrap WDEKs...");

        final List<CompletableFuture<WrappedDekDetails>> rewrapFutures = new ArrayList<>();
        for (WrappedDekDetails wdekDetails : allWdeks) {
            final String oldKekId = wdekDetails.kekId();
            logger.info("Re-wrapping WDEK for {}/{} version {} from KEK {} to {}",
                       wdekDetails.projectName(), wdekDetails.repoName(),
                       wdekDetails.dekVersion(), oldKekId, kekId);

            final CompletableFuture<WrappedDekDetails> rewrapFuture =
                    keyWrapper.rewrap(wdekDetails.wrappedDek(), oldKekId, kekId)
                              .handle((wrappedDek, cause) -> {
                                  if (cause != null) {
                                      logger.warn("Failed to rewrap WDEK for {}",
                                                  projectRepoVersion(wdekDetails.projectName(),
                                                                     wdekDetails.repoName(),
                                                                     wdekDetails.dekVersion()), cause);
                                      return null;
                                  }
                                  if (oldKekId.equals(kekId) && wdekDetails.wrappedDek().equals(wrappedDek)) {
                                      logger.info("WDEK for {} is already wrapped with the target KEK {}. " +
                                                  "Skipping rewrap.",
                                                  projectRepoVersion(wdekDetails.projectName(),
                                                                     wdekDetails.repoName(),
                                                                     wdekDetails.dekVersion()),
                                                  kekId);
                                      return null;
                                  }

                                  return new WrappedDekDetails(wrappedDek,
                                                               wdekDetails.dekVersion(),
                                                               kekId,
                                                               wdekDetails.creationInstant(),
                                                               wdekDetails.projectName(),
                                                               wdekDetails.repoName());
                              });
            rewrapFutures.add(rewrapFuture);
        }

        return CompletableFuture.allOf(rewrapFutures.toArray(new CompletableFuture[0]))
                                .thenAccept(unused -> {
                                    final List<WrappedDekDetails> collected =
                                            rewrapFutures.stream().map(CompletableFuture::join)
                                                         .filter(Objects::nonNull)
                                                         .collect(toImmutableList());

                                    logger.info("Storing {} re-wrapped WDEKs", collected.size());
                                    final ColumnFamilyHandle wdekCf =
                                            rocksDbStorage.getColumnFamilyHandle(WDEK_COLUMN_FAMILY);

                                    int failedCount = 0;
                                    try (WriteBatch writeBatch = new WriteBatch();
                                         WriteOptions writeOptions = new WriteOptions()) {
                                        writeOptions.setSync(true);

                                        for (WrappedDekDetails newWdekDetails : collected) {
                                            final byte[] wdekBytes;
                                            try {
                                                wdekBytes = Jackson.writeValueAsBytes(newWdekDetails);
                                            } catch (JsonProcessingException e) {
                                                failedCount++;
                                                logger.warn("Failed to serialize re-wrapped WDEK for {}",
                                                            projectRepoVersion(newWdekDetails.projectName(),
                                                                               newWdekDetails.repoName(),
                                                                               newWdekDetails.dekVersion()),
                                                            e);
                                                continue;
                                            }
                                            final byte[] wdekKeyBytes = repoWdekDbKey(
                                                    newWdekDetails.projectName(),
                                                    newWdekDetails.repoName(),
                                                    newWdekDetails.dekVersion());
                                            writeBatch.put(wdekCf, wdekKeyBytes, wdekBytes);
                                        }

                                        rocksDbStorage.write(writeOptions, writeBatch);
                                        logger.info("Successfully re-wrapped {} WDEKs",
                                                    collected.size() - failedCount);
                                    } catch (RocksDBException e) {
                                        throw new EncryptionStorageException(
                                                "Failed to store re-wrapped WDEKs", e);
                                    }
                                });
    }

    private static boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    Map<String, Map<String, byte[]>> getAllData() {
        final Map<String, Map<String, byte[]>> allData = new HashMap<>();
        try (Snapshot snapshot = rocksDbStorage.getSnapshot();
             ReadOptions readOptions = new ReadOptions().setSnapshot(snapshot)) {

            for (Map.Entry<String, ColumnFamilyHandle> entry : rocksDbStorage.getAllColumnFamilyHandles()
                                                                             .entrySet()) {
                final String cfName = entry.getKey();
                final ColumnFamilyHandle cfHandle = entry.getValue();
                final Map<String, byte[]> cfData = new HashMap<>();

                try (RocksIterator iterator = rocksDbStorage.newIterator(cfHandle, readOptions)) {
                    iterator.seekToFirst();
                    while (iterator.isValid()) {
                        final String key = new String(iterator.key(), StandardCharsets.UTF_8);
                        cfData.put(key, iterator.value());
                        iterator.next();
                    }
                }
                allData.put(cfName, cfData);
            }
        }

        return allData;
    }
}
