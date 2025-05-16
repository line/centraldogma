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

public final class TestKeyManagementService implements KeyManagementService {

    @Override
    public CompletableFuture<byte[]> wrapDek(byte[] dek) {
        final byte[] prefix = "wrapped-".getBytes();
        final byte[] wdek = new byte[prefix.length + dek.length];
        System.arraycopy(prefix, 0, wdek, 0, prefix.length);
        System.arraycopy(dek, 0, wdek, prefix.length, dek.length);
        return CompletableFuture.completedFuture(wdek);
    }

    @Override
    public CompletableFuture<byte[]> unwrapWdek(byte[] wdek) {
        final int prefixLength = "wrapped-".length();
        if (wdek.length <= prefixLength) {
            throw new IllegalArgumentException("Invalid wrapped DEK length: " + wdek.length);
        }
        final byte[] dek = new byte[wdek.length - prefixLength];
        System.arraycopy(wdek, prefixLength, dek, 0, dek.length);
        return CompletableFuture.completedFuture(dek);
    }
}
