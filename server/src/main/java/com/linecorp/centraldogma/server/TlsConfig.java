/*
 * Copyright 2018 LINE Corporation
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
import com.google.errorprone.annotations.MustBeClosed;

/**
 * TLS configuration.
 */
public final class TlsConfig {

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
    public TlsConfig(@JsonProperty(value = "keyCertChainFile") @Nullable File keyCertChainFile,
                     @JsonProperty(value = "keyFile") @Nullable File keyFile,
                     @JsonProperty(value = "keyCertChain") @Nullable String keyCertChain,
                     @JsonProperty(value = "key") @Nullable String key,
                     @JsonProperty("keyPassword") @Nullable String keyPassword) {
        checkArgument(keyCertChainFile != null || keyCertChain != null,
                      "keyCertChainFile and keyCertChain cannot be null at the same time.");
        checkArgument(keyFile != null || key != null,
                      "keyFile and key cannot be null at the same time.");
        this.keyCertChainFile = keyCertChainFile;
        this.keyFile = keyFile;
        this.keyCertChain = keyCertChain;
        this.key = key;
        this.keyPassword = keyPassword;
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

    /**
     * Returns an {@link InputStream} of the certificate chain.
     */
    @MustBeClosed
    public InputStream keyCertChainInputStream() {
        return inputStream(keyCertChainFile, keyCertChain, "keyCertChain");
    }

    /**
     * Returns an {@link InputStream} of the private key.
     */
    @MustBeClosed
    public InputStream keyInputStream() {
        return inputStream(keyFile, key, "key");
    }

    private static InputStream inputStream(@Nullable File file,
                                           @Nullable String property, String propertyName) {
        if (file != null) {
            try {
                return Files.newInputStream(file.toPath());
            } catch (IOException e) {
                throw new RuntimeException("failed to create an input stream from " + file, e);
            }
        }

        assert property != null;
        final String converted = convertValue(property);
        if (converted == null) {
            throw new NullPointerException(propertyName + '(' + property + ") is converted to null.");
        }
        return new ByteArrayInputStream(converted.getBytes());
    }

    /**
     * Returns a password for the private key file. Return {@code null} if no password is set.
     */
    @JsonProperty
    @Nullable
    public String keyPassword() {
        return keyPassword;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("keyCertChainFile", keyCertChainFile)
                          .add("keyFile", keyFile)
                          .add("keyCertChain", keyCertChain)
                          .add("key", key)
                          .toString();
    }
}
