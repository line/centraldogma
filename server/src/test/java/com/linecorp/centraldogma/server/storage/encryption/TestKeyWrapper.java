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

import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public final class TestKeyWrapper implements KeyWrapper {

    @Override
    public CompletableFuture<String> wrap(byte[] dek, String kekId) {
        final byte[] prefix = "wrapped-".getBytes();
        final byte[] wdek = new byte[prefix.length + dek.length];
        System.arraycopy(prefix, 0, wdek, 0, prefix.length);
        System.arraycopy(dek, 0, wdek, prefix.length, dek.length);
        return CompletableFuture.completedFuture(Base64.getEncoder().encodeToString(wdek));
    }

    @Override
    public CompletableFuture<byte[]> unwrap(String wdek, String kekId) {
        final byte[] decoded = Base64.getDecoder().decode(wdek);
        final int prefixLength = "wrapped-".length();
        if (decoded.length <= prefixLength) {
            throw new IllegalArgumentException("Invalid wrapped DEK length: " + decoded.length);
        }

        final byte[] prefix = new byte[prefixLength];
        System.arraycopy(decoded, 0, prefix, 0, prefixLength);
        assert "wrapped-".equals(new String(prefix));

        final byte[] dek = new byte[decoded.length - prefixLength];
        System.arraycopy(decoded, prefixLength, dek, 0, dek.length);
        return CompletableFuture.completedFuture(dek);
    }

    @Override
    public CompletableFuture<String> rewrap(String wdek, String oldKekId, String newKekId) {
        return unwrap(wdek, oldKekId).thenCompose(dek -> wrap(dek, newKekId));
    }
}
