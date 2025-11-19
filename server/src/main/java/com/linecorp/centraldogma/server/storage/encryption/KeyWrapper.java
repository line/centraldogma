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

import java.util.concurrent.CompletableFuture;

/**
 * Manages the encryption keys used for encrypting and decrypting data at rest.
 */
public interface KeyWrapper {

    /**
     * Wraps the given data encryption key (DEK) using the key management service.
     *
     * @param dek the data encryption key to be wrapped.
     * @param kekId the key encryption key (KEK) identifier to be used for wrapping.
     */
    CompletableFuture<String> wrap(byte[] dek, String kekId);

    /**
     * Unwraps the given wrapped data encryption key (WDEK) using the key management service.
     */
    CompletableFuture<byte[]> unwrap(String wdek, String kekId);

    /**
     * Re-wraps the given wrapped data encryption key (WDEK).
     */
    CompletableFuture<String> rewrap(String wdek, String oldKekId, String newKekId);
}
