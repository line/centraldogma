/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

final class TagUtil {

    private static final String[] TABLE;

    static {
        TABLE = new String[256];
        for (int i = 0; i < TABLE.length; i++) {
            TABLE[i] = String.format("%02x/", i);
        }
    }

    /**
     * Generates a string by joining hex representation for each byte in {@code majorRevision} with '/' in
     * little endian order.
     * <p>
     * For example,
     * <ul>
     * <li>0x0d0c0b0a -> '0a/0b/0c/0d/'</li>
     * <li>0x00000b0a -> '0a/0b/'</li>
     * <li>0x00000001 -> '01/'</li>
     * </ul>
     * </p>
     *
     * @throws IllegalArgumentException if {@code majorRevision} is a zero or a negative value.
     */
    static String byteHexDirName(int majorRevision) {

        if (majorRevision <= 0) {
            throw new IllegalArgumentException("invalid majorRevision " + majorRevision + " (expected: > 0)");
        }

        final StringBuilder sb = new StringBuilder(16);
        final int shift = 8;

        for (;;) {
            sb.append(TABLE[majorRevision & 0xFF]);
            majorRevision >>>= shift;
            if (majorRevision == 0) {
                break;
            }
        }
        return sb.toString();
    }

    private TagUtil() {}
}
