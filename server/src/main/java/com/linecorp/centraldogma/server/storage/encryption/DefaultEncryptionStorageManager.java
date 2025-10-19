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
import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.aesSecretKey;
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

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.RocksObject;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;

import com.linecorp.centraldogma.server.auth.SessionKey;
import com.linecorp.centraldogma.server.auth.SessionMasterKey;
import com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.GitObjectMetadata;

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
    private final boolean encryptSessionCookie;

    DefaultEncryptionStorageManager(String rocksDbPath, boolean encryptSessionCookie) {
        this.encryptSessionCookie = encryptSessionCookie;
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
    public boolean encryptSessionCookie() {
        return encryptSessionCookie;
    }

    @Override
    public CompletableFuture<byte[]> generateWdek() {
        final byte[] dek = AesGcmSivCipher.generateAes256Key();
        return keyManagementService.wrap(dek);
    }

    @Override
    public CompletableFuture<SessionMasterKey> generateSessionMasterKey() {
        final byte[] masterKey = AesGcmSivCipher.generateAes256Key();
        // Generate the same size of salt: https://datatracker.ietf.org/doc/html/rfc5869#section-3.1
        // It doesn't have to be a secret value.
        final byte[] salt = AesGcmSivCipher.generateAes256Key();
        return keyManagementService.wrap(masterKey).thenApply(wrappedMasterKey -> {
            // Currently, we only use version 1 of the session master key.
            return new SessionMasterKey(wrappedMasterKey, salt, 1);
        });
    }

    @Override
    public void storeSessionMasterKey(SessionMasterKey sessionMasterKey) {
        requireNonNull(sessionMasterKey, "sessionMasterKey");
        final ColumnFamilyHandle wdekCf = columnFamilyHandlesMap.get(WDEK_COLUMN_FAMILY);
        final int version = sessionMasterKey.version();
        final byte[] masterKeyKey = sessionMasterKeyKey(version);
        try {
            final byte[] existing = rocksDb.get(wdekCf, masterKeyKey);
            if (existing != null) {
                throw new EncryptionEntryExistsException(
                        "Session master key of version " + version + " already exists");
            }
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to check the existence of session master key of version " +
                    version, e);
        }

        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            writeBatch.put(wdekCf, masterKeyKey, sessionMasterKey.wrappedMasterKey());
            writeBatch.put(wdekCf, sessionMasterKeySaltKey(version),
                           sessionMasterKey.salt());
            writeBatch.put(wdekCf, currentSessionMasterKeyVersionKey(),
                           Ints.toByteArray(version));
            rocksDb.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to store session master key of version " + version, e);
        }
    }

    private static byte[] sessionMasterKeyKey(int version) {
        return ("session/master/" + version).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sessionMasterKeySaltKey(int version) {
        return ("session/master/" + version + "/salt").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] currentSessionMasterKeyVersionKey() {
        return "session/master/current".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public CompletableFuture<SessionKey> getCurrentSessionKey() {
        final ColumnFamilyHandle wdekCf = columnFamilyHandlesMap.get(WDEK_COLUMN_FAMILY);
        final int version;
        try {
            final byte[] versionBytes = rocksDb.get(wdekCf, currentSessionMasterKeyVersionKey());
            if (versionBytes == null) {
                throw new EncryptionStorageException("Current session master key does not exist");
            }
            version = Ints.fromByteArray(versionBytes);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to get the current session master key", e);
        }

        final byte[] wrappedMasterKey;
        final byte[] salt;
        try {
            wrappedMasterKey = rocksDb.get(wdekCf, sessionMasterKeyKey(version));
            if (wrappedMasterKey == null) {
                throw new EncryptionStorageException(
                        "Session master key of version " + version + " does not exist");
            }
            salt = rocksDb.get(wdekCf, sessionMasterKeySaltKey(version));
            if (salt == null) {
                throw new EncryptionStorageException("Salt for session master key does not exist. version: " +
                                                     version);
            }
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to get the session master key of version " + version, e);
        }
        return keyManagementService.unwrap(wrappedMasterKey)
                                   .thenApply(masterKey -> SessionKey.of(masterKey, salt, version));
    }

    @Override
    public SecretKey getDek(String projectName, String repoName, int version) {
        final ColumnFamilyHandle wdekCf = columnFamilyHandlesMap.get(WDEK_COLUMN_FAMILY);
        final byte[] wdek;
        try {
            wdek = rocksDb.get(wdekCf, repoWdekDbKey(projectName, repoName, version));
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to get WDEK of " + projectRepoVersion(projectName, repoName, version), e);
        }

        if (wdek == null) {
            throw new EncryptionStorageException(
                    "WDEK of " + projectRepoVersion(projectName, repoName, version) + " does not exist");
        }

        final byte[] key;
        try {
            key = keyManagementService.unwrap(wdek).get(10, TimeUnit.SECONDS);
        } catch (Throwable t) {
            throw new EncryptionStorageException(
                    "Failed to unwrap WDEK of " + projectRepoVersion(projectName, repoName, version), t);
        }
        return aesSecretKey(key);
    }

    @Override
    public SecretKeyWithVersion getCurrentDek(String projectName, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        final int version;
        final ColumnFamilyHandle wdekCf = columnFamilyHandlesMap.get(WDEK_COLUMN_FAMILY);

        try {
            final byte[] versionBytes = rocksDb.get(wdekCf, repoCurrentWdekDbKey(projectName, repoName));
            if (versionBytes == null) {
                throw new EncryptionStorageException(
                        "Current WDEK of " + projectRepo(projectName, repoName) + " does not exist");
            }
            try {
                version = Ints.fromByteArray(versionBytes);
            } catch (IllegalArgumentException e) {
                throw new EncryptionStorageException(
                        "Failed to parse the current WDEK version of " + projectRepo(projectName, repoName) +
                        ". The version bytes: " + Arrays.toString(versionBytes), e);
            }
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to get the current WDEK of " + projectRepo(projectName, repoName), e);
        }

        return new SecretKeyWithVersion(getDek(projectName, repoName, version), version);
    }

    @Override
    public void storeWdek(String projectName, String repoName, byte[] wdek) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(wdek, "wdek");
        // Only version 1 is used for now. After implementing key rotation, other versions will be used.
        final int version = 1;

        final byte[] wdekKeyBytes = repoWdekDbKey(projectName, repoName, version);
        final ColumnFamilyHandle wdekCf = columnFamilyHandlesMap.get(WDEK_COLUMN_FAMILY);
        try {
            final byte[] existingWdek = rocksDb.get(wdekCf, wdekKeyBytes);
            if (existingWdek != null) {
                throw new EncryptionEntryExistsException(
                        "WDEK of " + projectRepoVersion(projectName, repoName, version) + " already exists");
            }
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to check the existence of WDEK for " +
                                                 projectRepoVersion(projectName, repoName, version), e);
        }

        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            writeBatch.put(wdekCf, wdekKeyBytes, wdek);
            writeBatch.put(wdekCf,
                           repoCurrentWdekDbKey(projectName, repoName), Ints.toByteArray(version));
            rocksDb.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to store WDEK of " + projectRepoVersion(projectName, repoName, version), e);
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

    @Override
    public void removeWdek(String projectName, String repoName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        final int version = 1; // We use version 1 for the current WDEK.
        try {
            try (WriteBatch writeBatch = new WriteBatch();
                 WriteOptions writeOptions = new WriteOptions()) {
                writeOptions.setSync(true);
                final ColumnFamilyHandle wdekCf = columnFamilyHandlesMap.get(WDEK_COLUMN_FAMILY);
                writeBatch.delete(wdekCf, repoWdekDbKey(projectName, repoName, version));
                writeBatch.delete(wdekCf, repoCurrentWdekDbKey(projectName, repoName));
                rocksDb.write(writeOptions, writeBatch);
            }
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to remove WDEK of " + projectRepoVersion(projectName, repoName, version), e);
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

        // Currently, only version 1 of the WDEK is used.
        final SecretKey dek = getDek(projectName, repoName, 1);

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
                                objectDek = gitObjectMetadata.objectDek(dek);
                            } catch (Exception e) {
                                throw new EncryptionStorageException(
                                        "Failed to get object dek for " +
                                        new String(metadataKey, StandardCharsets.UTF_8), e);
                            }

                            final byte[] key = AesGcmSivCipher.encrypt(objectDek,
                                                                       gitObjectMetadata.nonce(), idPart);
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
                    // MetadataValue: key version(4) + nonce(12)
                    if (metadataKey.length == rev2ShaPrefixBytes.length + 4) {
                        idPart = Arrays.copyOfRange(metadataKey, rev2ShaPrefixBytes.length, metadataKey.length);
                        if (metadataValue != null && metadataValue.length == 4 + NONCE_SIZE_BYTES) {
                            final byte[] nonce = new byte[NONCE_SIZE_BYTES];
                            System.arraycopy(metadataValue, 4, nonce, 0, NONCE_SIZE_BYTES);
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
                    if (metadataValue != null && metadataValue.length == 4 + NONCE_SIZE_BYTES) {
                        final byte[] nonce = new byte[NONCE_SIZE_BYTES];
                        System.arraycopy(metadataValue, 4, nonce, 0, NONCE_SIZE_BYTES);
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
    public Map<String, Map<String, byte[]>> getAllData() {
        final Map<String, Map<String, byte[]>> allData = new HashMap<>();

        try (Snapshot snapshot = rocksDb.getSnapshot();
             ReadOptions readOptions = new ReadOptions().setSnapshot(snapshot)) {

            for (Map.Entry<String, ColumnFamilyHandle> entry : columnFamilyHandlesMap.entrySet()) {
                final String cfName = entry.getKey();
                final ColumnFamilyHandle cfHandle = entry.getValue();
                final Map<String, byte[]> cfData = new HashMap<>();

                try (RocksIterator iterator = rocksDb.newIterator(cfHandle, readOptions)) {
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
