/*
 * Copyright 2024 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.server.CentralDogmaConfig.convertValue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.io.ByteStreams;
import com.google.errorprone.annotations.MustBeClosed;

/**
 * TLS configuration.
 */
public final class TlsConfig implements TlsConfigSpec {

    @Nullable
    private final File keyCertChainFile;
    @Nullable
    private final File keyFile;
    @Nullable
    private final String keyCertChain;
    @Nullable
    private final String key;
    @Nullable
    private final String keyPassword;

    /**
     * Creates an instance with the specified {@code keyCertChainFilePath}, {@code keyFilePath} and
     * {@code keyPassword}.
     */
    @JsonCreator
    public TlsConfig(@JsonProperty("keyCertChainFile") @Nullable File keyCertChainFile,
                     @JsonProperty("keyFile") @Nullable File keyFile,
                     @JsonProperty("keyCertChain") @Nullable String keyCertChain,
                     @JsonProperty("key") @Nullable String key,
                     @JsonProperty("keyPassword") @Nullable String keyPassword) {
        validate(keyCertChainFile, keyCertChain, "keyCertChainFile", "keyCertChain");
        validate(keyFile, key, "keyFile", "key");

        this.keyCertChainFile = keyCertChainFile;
        this.keyFile = keyFile;
        // keyCertChain and key are converted later when it's used.
        this.keyCertChain = keyCertChain;
        this.key = key;
        this.keyPassword = keyPassword;
    }

    private static void validate(@Nullable File fileName, @Nullable String name,
                                 String first, String second) {
        checkArgument(fileName != null || name != null,
                      "%s and %s cannot be null at the same time.", first, second);
        if (fileName != null && name != null) {
            throw new IllegalArgumentException(
                    String.format("%s and %s cannot be specified at the same time.", first, second));
        }
    }

    /**
     * Returns a certificates file which is created with the given {@code keyCertChainFilePath}.
     *
     * @deprecated Use {@link #keyCertChainInputStream()}.
     */
    @Nullable
    @JsonProperty
    @Deprecated
    public File keyCertChainFile() {
        return keyCertChainFile;
    }

    /**
     * Returns a private key file which is created with the given {@code keyFilePath}.
     *
     * @deprecated Use {@link #keyInputStream()}.
     */
    @Nullable
    @JsonProperty
    @Deprecated
    public File keyFile() {
        return keyFile;
    }

    @MustBeClosed
    @Override
    public InputStream keyCertChainInputStream() {
        return inputStream(keyCertChainFile, keyCertChain, "keyCertChain");
    }

    @MustBeClosed
    @Override
    public InputStream keyInputStream() {
        return inputStream(keyFile, key, "key");
    }

    private static InputStream inputStream(@Nullable File file,
                                           @Nullable String property, String propertyName) {
        if (file != null) {
            try (InputStream inputStream = Files.newInputStream(file.toPath())) {
                // Use byte array to avoid file descriptor leak.
                return new ByteArrayInputStream(ByteStreams.toByteArray(inputStream));
            } catch (IOException e) {
                throw new RuntimeException("failed to create an input stream from " + file, e);
            }
        }

        assert property != null;
        final String converted = convertValue(property, "tls." + propertyName);
        if (converted == null) {
            throw new NullPointerException(propertyName + '(' + property + ") is converted to null.");
        }
        return new ByteArrayInputStream(converted.getBytes());
    }

    @JsonProperty
    @Nullable
    @Override
    public String keyPassword() {
        return convertValue(keyPassword, "tls.keyPassword");
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("keyCertChainFile", keyCertChainFile)
                          .add("keyFile", keyFile)
                          .toString();
    }
}
