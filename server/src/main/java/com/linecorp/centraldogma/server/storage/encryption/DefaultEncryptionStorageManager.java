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

import static com.linecorp.centraldogma.server.internal.storage.EncryptionKeyUtil.NONCE_SIZE_BYTES;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage.OBJS;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage.REFS;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage.REV2SHA;
import static java.util.Objects.requireNonNull;
import static org.rocksdb.RocksDB.listColumnFamilies;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.server.internal.storage.EncryptionKeyUtil;

final class DefaultEncryptionStorageManager implements EncryptionStorageManager {

    private static final Logger logger = LoggerFactory.getLogger(DefaultEncryptionStorageManager.class);

    private static final String ENCRYPTION_METADATA_COLUMN_FAMILY = "encryption_metadata";

    static final String ROCKSDB_PATH = "_rocks";

    private final KeyManagementService keyManagementService;
    private final DBOptions options;
    private final RocksDB rocksDb;
    private final ColumnFamilyHandle defaultColumnFamilyHandle;
    private final ColumnFamilyHandle metadataColumnFamilyHandle;

    DefaultEncryptionStorageManager(String rocksDbPath) {
        final List<KeyManagementService> keyManagementServices = ImmutableList.copyOf(ServiceLoader.load(
                KeyManagementService.class, EncryptionStorageManager.class.getClassLoader()));
        if (keyManagementServices.size() != 1) {
            throw new IllegalStateException(
                    "A single KeyManagementService implementation must be provided. found: " +
                    keyManagementServices);
        }
        keyManagementService = keyManagementServices.get(0);
        RocksDB.loadLibrary();
        try (Options options = new Options().setCreateIfMissing(true);
             RocksDB rocksDb = RocksDB.open(options, rocksDbPath)) {
            final List<byte[]> columnFamilies = listColumnFamilies(options, rocksDbPath);
            boolean foundEncryptionMetadata = false;
            for (byte[] columnFamily : columnFamilies) {
                final String columnFamilyName = new String(columnFamily, StandardCharsets.UTF_8);
                if (ENCRYPTION_METADATA_COLUMN_FAMILY.equals(columnFamilyName)) {
                    foundEncryptionMetadata = true;
                    break;
                }
            }
            if (!foundEncryptionMetadata) {
                try (ColumnFamilyHandle columnFamilyHandle = rocksDb.createColumnFamily(
                        new ColumnFamilyDescriptor(ENCRYPTION_METADATA_COLUMN_FAMILY.getBytes(),
                                                   new ColumnFamilyOptions()))) {
                    assert columnFamilyHandle != null;
                }
            }
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to open RocksDB", e);
        }

        // open DB with default and encryption metadata column family
        final List<ColumnFamilyDescriptor> columnFamilyDescriptors = new ArrayList<>();
        columnFamilyDescriptors.add(new ColumnFamilyDescriptor(
                RocksDB.DEFAULT_COLUMN_FAMILY, new ColumnFamilyOptions()));
        columnFamilyDescriptors.add(new ColumnFamilyDescriptor(
                ENCRYPTION_METADATA_COLUMN_FAMILY.getBytes(), new ColumnFamilyOptions()));
        options = new DBOptions();
        try {
            final List<ColumnFamilyHandle> columnFamilyHandles = new ArrayList<>();
            rocksDb = RocksDB.open(options, rocksDbPath, columnFamilyDescriptors, columnFamilyHandles);
            ColumnFamilyHandle defaultColumnFamilyHandle = null;
            ColumnFamilyHandle metadataColumnFamilyHandle = null;
            for (ColumnFamilyHandle handle : columnFamilyHandles) {
                if (Arrays.equals(RocksDB.DEFAULT_COLUMN_FAMILY, handle.getName())) {
                    defaultColumnFamilyHandle = handle;
                } else if (Arrays.equals(ENCRYPTION_METADATA_COLUMN_FAMILY.getBytes(StandardCharsets.UTF_8),
                                         handle.getName())) {
                    metadataColumnFamilyHandle = handle;
                }
            }
            if (defaultColumnFamilyHandle == null || metadataColumnFamilyHandle == null) {
                throw new EncryptionStorageException("Failed to get required column family handles.");
            }
            this.defaultColumnFamilyHandle = defaultColumnFamilyHandle;
            this.metadataColumnFamilyHandle = metadataColumnFamilyHandle;
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to open RocksDB", e);
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public CompletableFuture<byte[]> generateWdek() {
        final byte[] dek = EncryptionKeyUtil.generateAes256Key();
        return keyManagementService.wrapDek(dek);
    }

    @Override
    public SecretKey getDek(String projectName, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        final byte[] wdek;
        try {
            wdek = rocksDb.get(repoWdekDbKey(projectName, repoName).getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to get WDEK of " + projectRepo(projectName, repoName), e);
        }

        if (wdek == null) {
            throw new EncryptionStorageException(
                    "WDEK of " + projectRepo(projectName, repoName) + " does not exist");
        }

        try {
            return new SecretKeySpec(keyManagementService.unwrapWdek(wdek).get(10, TimeUnit.SECONDS), "AES");
        } catch (Throwable t) {
            throw new EncryptionStorageException(
                    "Failed to unwrap WDEK of " + projectRepo(projectName, repoName), t);
        }
    }

    @Override
    public void storeWdek(String projectName, String repoName, byte[] wdek) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(wdek, "wdek");
        final byte[] wdekKeyBytes = repoWdekDbKey(projectName, repoName).getBytes(StandardCharsets.UTF_8);
        try {
            final byte[] existingWdek = rocksDb.get(wdekKeyBytes);
            if (existingWdek != null) {
                throw new EncryptionStorageException(
                        "WDEK of " + projectRepo(projectName, repoName) + " already exists");
            }
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to check existence of WDEK for " + projectRepo(projectName, repoName), e);
        }

        // RocksDB is thread-safe.
        try {
            rocksDb.put(wdekKeyBytes, wdek);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to store WDEK of " + projectRepo(projectName, repoName), e);
        }
    }

    private static String projectRepo(String projectName, String repoName) {
        return projectName + '/' + repoName;
    }

    private static String repoWdekDbKey(String projectName, String repoName) {
        return "wdeks/" + projectName + '/' + repoName;
    }

    @Override
    public void removeWdek(String projectName, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        try {
            rocksDb.delete(repoWdekDbKey(projectName, repoName).getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to remove WDEK of " + projectRepo(projectName, repoName), e);
        }
    }

    @Override
    public byte[] get(byte[] key) {
        requireNonNull(key, "key");
        try {
            return rocksDb.get(key);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to get value", e);
        }
    }

    @Override
    public byte[] getMetadata(byte[] metadataKey) {
        requireNonNull(metadataKey, "metadataKey");
        try {
            return rocksDb.get(metadataColumnFamilyHandle, metadataKey);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to get metadata. key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public void put(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value) {
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeBatch.put(metadataColumnFamilyHandle, metadataKey, metadataValue);
            writeBatch.put(defaultColumnFamilyHandle, key, value);
            rocksDb.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to write key-value with metadata. metadata key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public boolean containsMetadata(byte[] key) {
        requireNonNull(key, "key");
        try {
            final byte[] value = rocksDb.get(metadataColumnFamilyHandle, key);
            return value != null;
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to check existence of metadata. key: " +
                                                 new String(key, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public void delete(byte[] metadataKey, byte[] key) {
        requireNonNull(metadataKey, "metadataKey");
        requireNonNull(key, "key");
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeBatch.delete(metadataColumnFamilyHandle, metadataKey);
            writeBatch.delete(defaultColumnFamilyHandle, key);
            rocksDb.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to delete key-value with metadata. metadata key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public void deleteRepositoryData(String projectName, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        final SecretKey dek = getDek(projectName, repoName);

        final String projectRepoPrefix = projectRepo(projectName, repoName) + '/';
        final byte[] projectRepoPrefixBytes = projectRepoPrefix.getBytes(StandardCharsets.UTF_8);

        final byte[] objectKeyPrefixBytes = (projectRepoPrefix + OBJS).getBytes(StandardCharsets.UTF_8);
        final byte[] refsKeyPrefixBytes = (projectRepoPrefix + REFS).getBytes(StandardCharsets.UTF_8);
        final byte[] rev2ShaPrefixBytes = (projectRepoPrefix + REV2SHA).getBytes(StandardCharsets.UTF_8);

        int deletedCount = 0;
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions();
             RocksIterator iterator = rocksDb.newIterator(metadataColumnFamilyHandle)) {

            iterator.seek(projectRepoPrefixBytes);
            while (iterator.isValid()) {
                final byte[] metadataKey = iterator.key();

                if (!startsWith(metadataKey, projectRepoPrefixBytes)) {
                    break;
                }

                final byte[] metadataValue = iterator.value();
                byte[] keyInDefaultColumnFamily = null;
                final byte[] nonce;
                final byte[] idPart; // ObjectId, refName or revNum

                if (startsWith(metadataKey, objectKeyPrefixBytes)) {
                    // MetadataKey: project/repo/objs/<objectId_bytes(20)>
                    // MetadataValue: nonce(12) + type(4)
                    if (metadataKey.length == objectKeyPrefixBytes.length + 20) {
                        idPart = Arrays.copyOfRange(metadataKey, objectKeyPrefixBytes.length,
                                                    metadataKey.length);
                        if (metadataValue != null && metadataValue.length >= NONCE_SIZE_BYTES) {
                            nonce = Arrays.copyOfRange(metadataValue, 0, NONCE_SIZE_BYTES);
                            keyInDefaultColumnFamily = EncryptionKeyUtil.encrypt(dek, nonce, idPart);
                        } else {
                            logger.warn("Invalid metadata value for object key: {}",
                                        new String(metadataKey, StandardCharsets.UTF_8));
                        }
                    } else {
                        logger.warn("Invalid object metadata key length: {}",
                                    new String(metadataKey, StandardCharsets.UTF_8));
                    }
                } else if (startsWith(metadataKey, rev2ShaPrefixBytes)) {
                    // MetadataKey: project/repo/rev2sha/<revision_major_bytes(4)>
                    // MetadataValue: nonce(12)
                    if (metadataKey.length == rev2ShaPrefixBytes.length + 4) {
                        idPart = Arrays.copyOfRange(metadataKey, rev2ShaPrefixBytes.length, metadataKey.length);
                        nonce = metadataValue;
                        if (nonce != null && nonce.length == NONCE_SIZE_BYTES) {
                            keyInDefaultColumnFamily = EncryptionKeyUtil.encrypt(dek, nonce, idPart);
                        } else {
                            logger.warn("Invalid nonce (metadata value) for rev2sha key: {}",
                                        new String(metadataKey, StandardCharsets.UTF_8));
                        }
                    } else {
                        logger.warn("Invalid rev2sha metadata key length: {}",
                                    new String(metadataKey, StandardCharsets.UTF_8));
                    }
                } else if (startsWith(metadataKey, refsKeyPrefixBytes)) {
                    // MetadataKey: project/repo/refs/... (e.g., project/repo/refs/heads/master)
                    // idPart should be "refs/..." so copy from projectRepoPrefixBytes.length
                    // MetadataValue: nonce(12)
                    idPart = Arrays.copyOfRange(metadataKey, projectRepoPrefixBytes.length, metadataKey.length);
                    nonce = metadataValue;
                    if (nonce != null && nonce.length == NONCE_SIZE_BYTES) {
                        keyInDefaultColumnFamily = EncryptionKeyUtil.encrypt(dek, nonce, idPart);
                    } else {
                        logger.warn("Invalid nonce (metadata value) for ref key: {}",
                                    new String(metadataKey, StandardCharsets.UTF_8));
                    }
                } else {
                    logger.warn("Unknown metadata key pattern for prefix {}: {}",
                                projectRepoPrefix, new String(metadataKey, StandardCharsets.UTF_8));
                }

                writeBatch.delete(metadataColumnFamilyHandle, metadataKey);
                deletedCount++;
                if (keyInDefaultColumnFamily != null) {
                    writeBatch.delete(defaultColumnFamilyHandle, keyInDefaultColumnFamily);
                    deletedCount++;
                } else {
                    logger.warn("Failed to find the corresponding key for metadata key: {}.",
                                new String(metadataKey, StandardCharsets.UTF_8));
                }
                iterator.next();
            }

            if (deletedCount > 0) {
                rocksDb.write(writeOptions, writeBatch);
                logger.info("Deleted {} entries for repository {}/{}", deletedCount, projectName, repoName);
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

        removeWdek(projectName, repoName);
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

    @Override
    public void close() {
        try {
            defaultColumnFamilyHandle.close();
        } catch (Throwable t) {
            logger.warn("Failed to close default column family handle", t);
        }
        try {
            metadataColumnFamilyHandle.close();
        } catch (Throwable t) {
            logger.warn("Failed to close metadata column family handle", t);
        }
        try {
            rocksDb.close();
        } catch (Throwable t) {
            logger.warn("Failed to close RocksDB", t);
        }
        try {
            options.close();
        } catch (Throwable t) {
            logger.warn("Failed to close RocksDB options", t);
        }
    }
}
