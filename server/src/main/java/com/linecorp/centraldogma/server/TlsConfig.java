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

import static java.util.Objects.requireNonNull;

import java.io.File;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * TLS configuration.
 */
public final class TlsConfig {

    private final File keyCertChainFile;
    private final File keyFile;
    @Nullable
    private final String keyPassword;

    /**
     * Creates an instance with the specified {@code keyCertChainFilePath}, {@code keyFilePath} and
     * {@code keyPassword}.
     */
    @JsonCreator
    public TlsConfig(@JsonProperty(value = "keyCertChainFile", required = true) File keyCertChainFile,
                     @JsonProperty(value = "keyFile", required = true) File keyFile,
                     @JsonProperty("keyPassword") @Nullable String keyPassword) {
        this.keyCertChainFile = requireNonNull(keyCertChainFile, "keyCertChainFile");
        this.keyFile = requireNonNull(keyFile, "keyFile");
        this.keyPassword = keyPassword;
    }

    /**
     * Returns a certificates file which is created with the given {@code keyCertChainFilePath}.
     */
    @JsonProperty
    public File keyCertChainFile() {
        return keyCertChainFile;
    }

    /**
     * Returns a private key file which is created with the given {@code keyFilePath}.
     */
    @JsonProperty
    public File keyFile() {
        return keyFile;
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
        return MoreObjects.toStringHelper(this)
                          .add("keyCertChainFile", keyCertChainFile)
                          .add("keyFile", keyFile)
                          .add("keyPassword", keyPassword)
                          .toString();
    }
}
