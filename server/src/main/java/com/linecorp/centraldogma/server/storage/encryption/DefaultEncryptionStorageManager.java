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

import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.NONCE_SIZE_BYTES;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage.OBJS;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage.REFS;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage.REV2SHA;
import static java.util.Objects.requireNonNull;
import static org.eclipse.jgit.lib.Constants.HEAD;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.RocksObject;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher;

final class DefaultEncryptionStorageManager implements EncryptionStorageManager {

    private static final Logger logger = LoggerFactory.getLogger(DefaultEncryptionStorageManager.class);

    private static final String WDEK_COLUMN_FAMILY = "wdek";
    private static final String ENCRYPTION_METADATA_COLUMN_FAMILY = "encryption_metadata";
    private static final String ENCRYPTED_OBJECT_COLUMN_FAMILY = "encrypted_object";
    // This column family is used to store the ref to object ID and revision to object ID mapping.
    private static final String ENCRYPTED_OBJECT_ID_COLUMN_FAMILY = "encrypted_object_id";

    private static final List<String> ALL_COLUMN_FAMILY_NAMES = ImmutableList.of(
            "default", WDEK_COLUMN_FAMILY, ENCRYPTION_METADATA_COLUMN_FAMILY,
            ENCRYPTED_OBJECT_COLUMN_FAMILY, ENCRYPTED_OBJECT_ID_COLUMN_FAMILY
    );

    private static final int BATCH_WRITE_SIZE = 1000;

    static final String ROCKSDB_PATH = "_rocks";

    private final KeyManagementService keyManagementService;
    private final RocksDB rocksDb;
    private final DBOptions dbOptions;
    private final Map<String, ColumnFamilyHandle> columnFamilyHandlesMap;

    private final BloomFilter bloomFilter;

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

        bloomFilter = new BloomFilter();

        final Map<String, ColumnFamilyOptions> cfNameToOptions = new HashMap<>();
        for (String cfName : ALL_COLUMN_FAMILY_NAMES) {
            cfNameToOptions.put(cfName, createColumnFamilyOptions(
                    ENCRYPTION_METADATA_COLUMN_FAMILY.equals(cfName)));
        }
        final List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
        for (String cfName : ALL_COLUMN_FAMILY_NAMES) {
            cfDescriptors.add(new ColumnFamilyDescriptor(
                    cfName.getBytes(StandardCharsets.UTF_8), cfNameToOptions.get(cfName)
            ));
        }

        dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);

        final List<ColumnFamilyHandle> openedHandlesList = new ArrayList<>();
        try {
            rocksDb = RocksDB.open(dbOptions, rocksDbPath, cfDescriptors, openedHandlesList);
        } catch (RocksDBException e) {
            cfNameToOptions.values().forEach(ColumnFamilyOptions::close);
            dbOptions.close();
            throw new EncryptionStorageException("Failed to open RocksDB with column families at " +
                                                 rocksDbPath, e);
        }

        final ImmutableMap.Builder<String, ColumnFamilyHandle> handlesMapBuilder = ImmutableMap.builder();
        for (ColumnFamilyHandle handle : openedHandlesList) {
            try {
                handlesMapBuilder.put(new String(handle.getName(), StandardCharsets.UTF_8), handle);
            } catch (RocksDBException e) {
                openedHandlesList.forEach(DefaultEncryptionStorageManager::closeSilently);
                closeSilently(rocksDb);
                cfNameToOptions.values().forEach(ColumnFamilyOptions::close);
                dbOptions.close();
                throw new EncryptionStorageException("Failed to get name for a column family handle", e);
            }
        }
        columnFamilyHandlesMap = handlesMapBuilder.build();

        for (String cfName : ALL_COLUMN_FAMILY_NAMES) {
            if (!columnFamilyHandlesMap.containsKey(cfName)) {
                close();
                throw new EncryptionStorageException("Column family handle not found for: " + cfName);
            }
        }
    }

    private static void closeSilently(RocksObject obj) {
        try {
            obj.close();
        } catch (Exception e) {
            logger.warn("Failed to close RocksObject silently", e);
        }
    }

    private ColumnFamilyOptions createColumnFamilyOptions(boolean withBloomFilter) {
        final ColumnFamilyOptions columnFamilyOptions = new ColumnFamilyOptions()
                // No compression is used for the encrypted data and nonce.
                .setCompressionType(CompressionType.NO_COMPRESSION);
        if (!withBloomFilter) {
            return columnFamilyOptions;
        }
        return columnFamilyOptions.setTableFormatConfig(
                new BlockBasedTableConfig().setFilterPolicy(bloomFilter));
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public CompletableFuture<byte[]> generateWdek() {
        final byte[] dek = AesGcmSivCipher.generateAes256Key();
        return keyManagementService.wrap(dek);
    }

    @Override
    public SecretKey getDek(String projectName, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        final byte[] wdek;
        try {
            wdek = rocksDb.get(columnFamilyHandlesMap.get(WDEK_COLUMN_FAMILY),
                               repoWdekDbKey(projectName, repoName).getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to get WDEK of " + projectRepo(projectName, repoName), e);
        }

        if (wdek == null) {
            throw new EncryptionStorageException(
                    "WDEK of " + projectRepo(projectName, repoName) + " does not exist");
        }

        try {
            return new SecretKeySpec(keyManagementService.unwrap(wdek).get(10, TimeUnit.SECONDS), "AES");
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
            final byte[] existingWdek = rocksDb.get(columnFamilyHandlesMap.get(WDEK_COLUMN_FAMILY),
                                                    wdekKeyBytes);
            if (existingWdek != null) {
                throw new EncryptionStorageException(
                        "WDEK of " + projectRepo(projectName, repoName) + " already exists");
            }
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to check existence of WDEK for " + projectRepo(projectName, repoName), e);
        }

        // RocksDB is thread-safe.
        try (WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            rocksDb.put(columnFamilyHandlesMap.get(WDEK_COLUMN_FAMILY), writeOptions, wdekKeyBytes, wdek);
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
            rocksDb.delete(columnFamilyHandlesMap.get(WDEK_COLUMN_FAMILY),
                           repoWdekDbKey(projectName, repoName).getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to remove WDEK of " + projectRepo(projectName, repoName), e);
        }
    }

    @Override
    public byte[] getObject(byte[] key, byte[] metadataKey) {
        requireNonNull(key, "key");
        requireNonNull(metadataKey, "metadataKey");
        try {
            return rocksDb.get(columnFamilyHandlesMap.get(ENCRYPTED_OBJECT_COLUMN_FAMILY), key);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to get object. metadata key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public byte[] getObjectId(byte[] key, byte[] metadataKey) {
        requireNonNull(key, "key");
        requireNonNull(metadataKey, "metadataKey");
        try {
            return rocksDb.get(columnFamilyHandlesMap.get(ENCRYPTED_OBJECT_ID_COLUMN_FAMILY), key);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to get object ID. metadata key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public byte[] getMetadata(byte[] metadataKey) {
        requireNonNull(metadataKey, "metadataKey");
        try {
            return rocksDb.get(columnFamilyHandlesMap.get(ENCRYPTION_METADATA_COLUMN_FAMILY), metadataKey);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to get metadata. key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public void putObject(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value) {
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            writeBatch.put(columnFamilyHandlesMap.get(ENCRYPTION_METADATA_COLUMN_FAMILY),
                           metadataKey, metadataValue);
            writeBatch.put(columnFamilyHandlesMap.get(ENCRYPTED_OBJECT_COLUMN_FAMILY), key, value);
            rocksDb.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to write object key-value with metadata. metadata key: " +
                    new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public void putObjectId(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value,
                            @Nullable byte[] previousKeyToRemove) {
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            writeBatch.put(columnFamilyHandlesMap.get(ENCRYPTION_METADATA_COLUMN_FAMILY),
                           metadataKey, metadataValue);
            final ColumnFamilyHandle objectIdcolumnFamilyHandle =
                    columnFamilyHandlesMap.get(ENCRYPTED_OBJECT_ID_COLUMN_FAMILY);
            writeBatch.put(objectIdcolumnFamilyHandle, key, value);
            if (previousKeyToRemove != null) {
                writeBatch.delete(objectIdcolumnFamilyHandle, previousKeyToRemove);
            }
            rocksDb.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to write object key-value with metadata. metadata key: " +
                    new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public boolean containsMetadata(byte[] key) {
        requireNonNull(key, "key");
        try {
            final byte[] value =
                    rocksDb.get(columnFamilyHandlesMap.get(ENCRYPTION_METADATA_COLUMN_FAMILY), key);
            return value != null;
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to check existence of metadata. key: " +
                                                 new String(key, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public void deleteObjectId(byte[] metadataKey, byte[] key) {
        requireNonNull(metadataKey, "metadataKey");
        requireNonNull(key, "key");
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            writeBatch.delete(columnFamilyHandlesMap.get(ENCRYPTION_METADATA_COLUMN_FAMILY), metadataKey);
            writeBatch.delete(columnFamilyHandlesMap.get(ENCRYPTED_OBJECT_ID_COLUMN_FAMILY), key);
            rocksDb.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to delete object ID key-value with metadata. metadata key: " +
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
        final byte[] headKeyBytes = (projectRepoPrefix + HEAD).getBytes(StandardCharsets.UTF_8);
        final byte[] rev2ShaPrefixBytes = (projectRepoPrefix + REV2SHA).getBytes(StandardCharsets.UTF_8);

        int totalDeletedCount = 0;
        int operationsInCurrentBatch = 0;

        final ColumnFamilyHandle metadataColumnFamilyHandle =
                columnFamilyHandlesMap.get(ENCRYPTION_METADATA_COLUMN_FAMILY);
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
                            final byte[] key = AesGcmSivCipher.encrypt(dek, nonce, idPart);
                            writeBatch.delete(columnFamilyHandlesMap.get(ENCRYPTED_OBJECT_COLUMN_FAMILY), key);
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
                } else if (startsWith(metadataKey, rev2ShaPrefixBytes)) {
                    // MetadataKey: project/repo/rev2sha/<revision_major_bytes(4)>
                    // MetadataValue: nonce(12)
                    if (metadataKey.length == rev2ShaPrefixBytes.length + 4) {
                        idPart = Arrays.copyOfRange(metadataKey, rev2ShaPrefixBytes.length, metadataKey.length);
                        nonce = metadataValue;
                        if (nonce != null && nonce.length == NONCE_SIZE_BYTES) {
                            final byte[] key = AesGcmSivCipher.encrypt(dek, nonce, idPart);
                            writeBatch.delete(columnFamilyHandlesMap.get(ENCRYPTED_OBJECT_ID_COLUMN_FAMILY),
                                              key);
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
                    // MetadataValue: nonce(12)
                    idPart = Arrays.copyOfRange(metadataKey, projectRepoPrefixBytes.length, metadataKey.length);
                    nonce = metadataValue;
                    if (nonce != null && nonce.length == NONCE_SIZE_BYTES) {
                        final byte[] key = AesGcmSivCipher.encrypt(dek, nonce, idPart);
                        writeBatch.delete(columnFamilyHandlesMap.get(ENCRYPTED_OBJECT_ID_COLUMN_FAMILY), key);
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

                writeBatch.delete(metadataColumnFamilyHandle, metadataKey);
                operationsInCurrentBatch++;
                totalDeletedCount++;
                if (operationsInCurrentBatch >= BATCH_WRITE_SIZE) {
                    if (writeBatch.count() > 0) {
                        writeOptions.setSync(true);
                        rocksDb.write(writeOptions, writeBatch);
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
                rocksDb.write(writeOptions, writeBatch);
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
        bloomFilter.close();
        for (Map.Entry<String, ColumnFamilyHandle> entry : columnFamilyHandlesMap.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Throwable t) {
                logger.warn("Failed to close column family handle: {}", entry.getKey(), t);
            }
        }
        try {
            rocksDb.close();
        } catch (Throwable t) {
            logger.warn("Failed to close RocksDB", t);
        }
        try {
            dbOptions.close();
        } catch (Throwable t) {
            logger.warn("Failed to close DBOptions", t);
        }
    }
}
