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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

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

final class RocksDBStorage {

    private static final Logger logger = LoggerFactory.getLogger(RocksDBStorage.class);

    static final String WDEK_COLUMN_FAMILY = "wdek";
    static final String ENCRYPTION_METADATA_COLUMN_FAMILY = "encryption_metadata";
    static final String ENCRYPTED_OBJECT_COLUMN_FAMILY = "encrypted_object";
    // This column family is used to store the ref to object ID and revision to object ID mapping.
    static final String ENCRYPTED_OBJECT_ID_COLUMN_FAMILY = "encrypted_object_id";

    private static final List<String> ALL_COLUMN_FAMILY_NAMES = ImmutableList.of(
            "default", WDEK_COLUMN_FAMILY, ENCRYPTION_METADATA_COLUMN_FAMILY,
            ENCRYPTED_OBJECT_COLUMN_FAMILY, ENCRYPTED_OBJECT_ID_COLUMN_FAMILY
    );

    private final RocksDB rocksDb;
    private final DBOptions dbOptions;
    private final Map<String, ColumnFamilyHandle> columnFamilyHandlesMap;
    private final BloomFilter bloomFilter;

    RocksDBStorage(String rocksDbPath) {
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
            bloomFilter.close();
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
                bloomFilter.close();
                openedHandlesList.forEach(RocksDBStorage::closeSilently);
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

    @Nullable
    byte[] get(String cfName, byte[] key) throws RocksDBException {
        return rocksDb.get(getColumnFamilyHandle(cfName), key);
    }

    void write(WriteOptions writeOptions, WriteBatch writeBatch) throws RocksDBException {
        rocksDb.write(writeOptions, writeBatch);
    }

    RocksIterator newIterator(ColumnFamilyHandle cfHandle) {
        return rocksDb.newIterator(cfHandle);
    }

    RocksIterator newIterator(ColumnFamilyHandle cfHandle, ReadOptions readOptions) {
        return rocksDb.newIterator(cfHandle, readOptions);
    }

    ColumnFamilyHandle getColumnFamilyHandle(String cfName) {
        final ColumnFamilyHandle handle = columnFamilyHandlesMap.get(cfName);
        if (handle == null) {
            throw new IllegalArgumentException("Column family not found: " + cfName);
        }
        return handle;
    }

    Map<String, ColumnFamilyHandle> getAllColumnFamilyHandles() {
        return columnFamilyHandlesMap;
    }

    Snapshot getSnapshot() {
        return rocksDb.getSnapshot();
    }

    void close() {
        bloomFilter.close();
        columnFamilyHandlesMap.values().forEach(RocksDBStorage::closeSilently);
        closeSilently(rocksDb);
        closeSilently(dbOptions);
    }

    private static void closeSilently(RocksObject obj) {
        try {
            obj.close();
        } catch (Exception e) {
            logger.warn("Failed to close RocksObject silently", e);
        }
    }
}
