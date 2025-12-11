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
package com.linecorp.centraldogma.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptionConfigTest {

    @Test
    void shouldCreateMarkerFileWhenEncryptionIsEnabled(@TempDir Path tempDir) throws IOException {
        final File dataDir = tempDir.toFile();
        final EncryptionConfig config = new EncryptionConfig(true, false, "test-kek-id");

        config.validateEncryptionState(dataDir);

        final Path markerFile = tempDir.resolve(".encryption-enabled");
        assertThat(markerFile).exists();
    }

    @Test
    void shouldNotCreateMarkerFileWhenEncryptionIsDisabled(@TempDir Path tempDir) {
        final File dataDir = tempDir.toFile();
        final EncryptionConfig config = new EncryptionConfig(false, false, null);

        config.validateEncryptionState(dataDir);

        final Path markerFile = tempDir.resolve(".encryption-enabled");
        assertThat(markerFile).doesNotExist();
    }

    @Test
    void shouldAllowReEnablingEncryption(@TempDir Path tempDir) throws IOException {
        final File dataDir = tempDir.toFile();
        final Path markerFile = tempDir.resolve(".encryption-enabled");

        // Create marker file to simulate previous encryption
        Files.createFile(markerFile);

        final EncryptionConfig config = new EncryptionConfig(true, false, "test-kek-id");

        // Should not throw exception
        config.validateEncryptionState(dataDir);

        assertThat(markerFile).exists();
    }

    @Test
    void shouldThrowExceptionWhenDisablingPreviouslyEnabledEncryption(@TempDir Path tempDir)
            throws IOException {
        final File dataDir = tempDir.toFile();
        final Path markerFile = tempDir.resolve(".encryption-enabled");

        // Create marker file to simulate previous encryption
        Files.createFile(markerFile);

        final EncryptionConfig config = new EncryptionConfig(false, false, null);

        assertThatThrownBy(() -> config.validateEncryptionState(dataDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot disable encryption after it has been enabled");
    }

    @Test
    void shouldCreateDataDirIfNotExists(@TempDir Path tempDir) {
        final File dataDir = tempDir.resolve("non-existent-dir").toFile();
        final EncryptionConfig config = new EncryptionConfig(true, false, "test-kek-id");

        config.validateEncryptionState(dataDir);

        assertThat(dataDir).exists();
        assertThat(dataDir.toPath().resolve(".encryption-enabled")).exists();
    }

    @Test
    void shouldThrowExceptionWhenKekIdIsNullForEnabledEncryption() {
        assertThatThrownBy(() -> new EncryptionConfig(true, false, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("kekId");
    }

    @Test
    void encryptSessionCookieShouldBeFalseWhenEncryptionIsDisabled() {
        final EncryptionConfig config = new EncryptionConfig(false, true, null);

        assertThat(config.enabled()).isFalse();
        assertThat(config.encryptSessionCookie()).isFalse();
    }

    @Test
    void encryptSessionCookieShouldBeTrueWhenBothAreEnabled() {
        final EncryptionConfig config = new EncryptionConfig(true, true, "test-kek-id");

        assertThat(config.enabled()).isTrue();
        assertThat(config.encryptSessionCookie()).isTrue();
    }
}
