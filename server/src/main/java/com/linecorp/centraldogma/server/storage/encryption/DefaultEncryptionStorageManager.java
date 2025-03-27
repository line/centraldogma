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

import static java.util.Objects.requireNonNull;
import static org.rocksdb.RocksDB.listColumnFamilies;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private final List<ColumnFamilyHandle> columnFamilyHandles = new ArrayList<>();

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
            rocksDb = RocksDB.open(options, rocksDbPath, columnFamilyDescriptors, columnFamilyHandles);
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
        if (rocksDb.keyMayExist(wdekKeyBytes, null)) {
            throw new EncryptionStorageException(
                    "WDEK of " + projectRepo(projectName, repoName) + " already exists");
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
            return rocksDb.get(columnFamilyHandles.get(1), metadataKey);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to get metadata. key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public void put(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value) {
        try (WriteBatch writeBatch = new WriteBatch()) {
            writeBatch.put(columnFamilyHandles.get(1), metadataKey, metadataValue);
            writeBatch.put(columnFamilyHandles.get(0), key, value);
            rocksDb.write(new WriteOptions(), writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to write key-value with metadata. metadata key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public boolean containsMetadata(byte[] key) {
        requireNonNull(key, "key");
        return rocksDb.keyMayExist(columnFamilyHandles.get(1), key, null);
    }

    @Override
    public void delete(byte[] metadataKey, byte[] key) {
        requireNonNull(metadataKey, "metadataKey");
        requireNonNull(key, "key");
        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeBatch.delete(columnFamilyHandles.get(1), metadataKey);
            writeBatch.delete(columnFamilyHandles.get(0), key);
            rocksDb.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to delete key-value with metadata. metadata key: " +
                                                 new String(metadataKey, StandardCharsets.UTF_8), e);
        }
    }

    @Override
    public void close() {
        try {
            for (final ColumnFamilyHandle handle : columnFamilyHandles) {
                handle.close();
            }
        } catch (Throwable t) {
            logger.warn("Failed to close RocksDB column family handles", t);
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
