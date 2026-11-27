/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.centraldogma.it.mirror;

import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.server.storage.encryption.KeyWrapper;

public final class MirrorTestKeyWrapper implements KeyWrapper {

    @Override
    public CompletableFuture<String> wrap(byte[] dek, String kekId) {
        return CompletableFuture.completedFuture(Base64.getEncoder().encodeToString(dek));
    }

    @Override
    public CompletableFuture<byte[]> unwrap(String wdek, String kekId) {
        return CompletableFuture.completedFuture(Base64.getDecoder().decode(wdek));
    }

    @Override
    public CompletableFuture<String> rewrap(String wdek, String oldKekId, String newKekId) {
        return CompletableFuture.completedFuture(wdek);
    }
}
