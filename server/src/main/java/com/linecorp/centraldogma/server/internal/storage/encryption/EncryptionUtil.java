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
package com.linecorp.centraldogma.server.internal.storage.encryption;

public final class EncryptionUtil {

    public static void putInt(byte[] metadata, int offset, int num) {
        metadata[offset] = (byte) (num >> 24);
        metadata[offset + 1] = (byte) (num >> 16);
        metadata[offset + 2] = (byte) (num >> 8);
        metadata[offset + 3] = (byte) num;
    }

    public static int getInt(byte[] metadata, int offset) {
        return ((metadata[offset] & 0xFF) << 24) |
               ((metadata[offset + 1] & 0xFF) << 16) |
               ((metadata[offset + 2] & 0xFF) << 8) |
               (metadata[offset + 3] & 0xFF);
    }

    private EncryptionUtil() {}
}
