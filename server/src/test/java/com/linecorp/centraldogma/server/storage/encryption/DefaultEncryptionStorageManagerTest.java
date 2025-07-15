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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultEncryptionStorageManagerTest {

    // Test Data
    private static final String PROJECT_NAME = "foo";
    private static final String REPO_NAME = "bar";
    private static final byte[] TEST_KEY_1 = "key1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TEST_VALUE_1 = "value1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TEST_KEY_2 = "key2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TEST_VALUE_2 = "value2".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private DefaultEncryptionStorageManager storageManager;

    @BeforeEach
    void setUp() {
        // Ensure ServiceLoader can find our test KMS
        // This relies on the META-INF/services file being in the test classpath
        final String rocksDbPath = tempDir.resolve(DefaultEncryptionStorageManager.ROCKSDB_PATH).toString();
        storageManager = new DefaultEncryptionStorageManager(rocksDbPath);
    }

    @AfterEach
    void tearDown() {
        if (storageManager != null) {
            storageManager.close(); // Ensure RocksDB is closed
        }
        // tempDir is automatically cleaned up by JUnit 5
    }

    @Test
    void enabled_shouldReturnTrue() {
        assertThat(storageManager.enabled()).isTrue();
    }

    @Test
    void generateWdek() throws Exception {
        final byte[] wdek = storageManager.generateWdek().join();
        assertThat(wdek).isNotNull();
        assertThat(wdek).startsWith("wrapped-".getBytes());
        // Check DEK length (AES-256 = 32 bytes)
        assertThat(wdek.length).isEqualTo("wrapped-".length() + 32);
    }

    @Test
    void storeAndGetDek() throws Exception {
        final byte[] wdek = storageManager.generateWdek().join();

        assertThatThrownBy(() -> storageManager.getCurrentDek(PROJECT_NAME, REPO_NAME))
                .isInstanceOf(EncryptionStorageException.class)
                .hasMessageContaining("WDEK of " + PROJECT_NAME + '/' + REPO_NAME + " does not exist");

        storageManager.storeWdek(PROJECT_NAME, REPO_NAME, wdek);
        final SecretKey dek = storageManager.getDek(PROJECT_NAME, REPO_NAME, 1);

        // 4. Verify
        assertThat(dek).isNotNull();
        assertThat(dek.getAlgorithm()).isEqualTo("AES");
        assertThat(dek.getEncoded()).hasSize(32); // AES-256 key size

        // Try storing again
        assertThatThrownBy(() -> storageManager.storeWdek(PROJECT_NAME, REPO_NAME, wdek))
                .isInstanceOf(EncryptionStorageException.class)
                .hasMessageContaining("WDEK of " + PROJECT_NAME + '/' + REPO_NAME + "/1 already exists");

        assertThat(storageManager.getDek(PROJECT_NAME, REPO_NAME, 1)).isNotNull();

        storageManager.removeWdek(PROJECT_NAME, REPO_NAME);

        // Verify it's gone
        assertThatThrownBy(() -> storageManager.getDek(PROJECT_NAME, REPO_NAME, 1))
                .isInstanceOf(EncryptionStorageException.class);
    }

    @Test
    void removeWdek_whenNotExists_shouldNotFail() {
        storageManager.removeWdek(PROJECT_NAME, "nonExistentRepo");
    }

    @Test
    void putWithMetadata() throws Exception {
        assertThat(storageManager.containsMetadata(TEST_KEY_1)).isFalse();
        assertThat(storageManager.containsMetadata(TEST_KEY_2)).isFalse();

        storageManager.putObject(TEST_KEY_1, TEST_VALUE_1, TEST_KEY_2, TEST_VALUE_2);

        assertThat(storageManager.getMetadata(TEST_KEY_1)).isEqualTo(TEST_VALUE_1);
        assertThat(storageManager.getObject(TEST_KEY_2, TEST_KEY_1)).isEqualTo(TEST_VALUE_2);
    }

    @Test
    void deleteObjectId() {
        storageManager.putObjectId(TEST_KEY_1, TEST_VALUE_1, TEST_KEY_2, TEST_VALUE_2, null);
        assertThat(storageManager.containsMetadata(TEST_KEY_1)).isTrue();
        storageManager.deleteObjectId(TEST_KEY_1, TEST_KEY_2);
        assertThat(storageManager.containsMetadata(TEST_KEY_1)).isFalse();
    }

    @Test
    void deleteObjectId_withNonExistent_shouldNotFail() {
        storageManager.putObjectId(TEST_KEY_1, TEST_VALUE_1, TEST_KEY_2, TEST_VALUE_2, null);
        storageManager.deleteObjectId(TEST_KEY_1, "nonExistent".getBytes());
        assertThat(storageManager.containsMetadata(TEST_KEY_1)).isFalse();
        // No exception
    }
}
