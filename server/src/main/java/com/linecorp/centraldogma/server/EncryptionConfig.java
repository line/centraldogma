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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * Encryption configuration.
 */
public final class EncryptionConfig {

    private static final String ENCRYPTION_MARKER_FILE_NAME = ".encryption-enabled";

    private final boolean enabled;
    private final boolean encryptSessionCookie;
    @Nullable
    private final String kekId;

    /**
     * Creates an instance.
     */
    @JsonCreator
    public EncryptionConfig(@JsonProperty("enabled") @Nullable Boolean enabled,
                            @JsonProperty("encryptSessionCookie")
                            @Nullable Boolean encryptSessionCookie,
                            @JsonProperty("kekId") @Nullable String kekId) {
        this.enabled = firstNonNull(enabled, false);
        this.encryptSessionCookie = this.enabled && firstNonNull(encryptSessionCookie, false);
        if (this.enabled) {
            requireNonNull(kekId, "kekId");
        }
        this.kekId = kekId;
    }

    /**
     * Validates the encryption state with the data directory.
     * This method checks if encryption was previously enabled and prevents disabling it.
     *
     * @param dataDir the data directory where the marker file is stored
     * @throws IllegalStateException if encryption was previously enabled but is now disabled
     */
    void validateEncryptionState(File dataDir) {
        requireNonNull(dataDir, "dataDir");
        final Path markerFilePath = dataDir.toPath().resolve(ENCRYPTION_MARKER_FILE_NAME);
        final boolean wasEncryptionEnabled = Files.exists(markerFilePath);

        if (wasEncryptionEnabled && !enabled) {
            throw new IllegalStateException(
                    "Cannot disable encryption after it has been enabled. " +
                    "Encryption was previously enabled. To proceed, you must keep encryption enabled.");
        }

        if (enabled && !wasEncryptionEnabled) {
            // Create the marker file to indicate encryption has been enabled
            try {
                Files.createDirectories(dataDir.toPath());
                final String markerContent =
                        "# DO NOT DELETE THIS FILE\n" +
                        "#\n" +
                        "# This file indicates that encryption has been enabled for this " +
                        "Central Dogma server.\n" +
                        "# Once encryption is enabled, it cannot be disabled.\n" +
                        "#\n" +
                        "# Deleting this file may cause the server to malfunction or fail to start.\n";
                Files.write(markerFilePath, markerContent.getBytes());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to create encryption marker file: " + markerFilePath, e);
            }
        }
    }

    /**
     * Returns whether encryption is enabled.
     */
    @JsonProperty
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns whether to encrypt session cookies.
     */
    @JsonProperty
    public boolean encryptSessionCookie() {
        return encryptSessionCookie;
    }

    /**
     * Returns the Key Encryption Key (KEK) ID.
     */
    @JsonProperty
    @Nullable
    public String kekId() {
        return kekId;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("enabled", enabled)
                          .add("encryptSessionCookie", encryptSessionCookie)
                          .add("kekId", kekId)
                          .toString();
    }
}

