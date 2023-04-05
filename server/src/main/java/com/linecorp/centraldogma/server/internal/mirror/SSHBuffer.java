/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.mirror;

import com.linecorp.armeria.internal.shaded.bouncycastle.util.Arrays;
import com.linecorp.armeria.internal.shaded.bouncycastle.util.Strings;

class SSHBuffer {

    // Forked from https://github.com/bcgit/bc-java/blob/master/core/src/main/java/org/bouncycastle/crypto/util/SSHBuffer.java
    
    private final byte[] buffer;
    private int pos;

    SSHBuffer(byte[] magic, byte[] buffer) {
        this.buffer = buffer;
        for (int i = 0; i != magic.length; i++) {
            if (magic[i] != buffer[i]) {
                throw new IllegalArgumentException("magic-number incorrect");
            }
        }
        pos += magic.length;
    }

    SSHBuffer(byte[] buffer) {
        this.buffer = buffer;
    }

    int readU32() {
        if (pos > (buffer.length - 4)) {
            throw new IllegalArgumentException("4 bytes for U32 exceeds buffer.");
        }

        int i = (buffer[pos++] & 0xFF) << 24;
        i |= (buffer[pos++] & 0xFF) << 16;
        i |= (buffer[pos++] & 0xFF) << 8;
        i |= buffer[pos++] & 0xFF;

        return i;
    }

    String readString() {
        return Strings.fromByteArray(readBlock());
    }

    byte[] readBlock() {
        final int len = readU32();
        if (len == 0) {
            return new byte[0];
        }

        if (pos > (buffer.length - len)) {
            throw new IllegalArgumentException("not enough data for block");
        }

        final int start = pos;
        pos += len;
        return Arrays.copyOfRange(buffer, start, pos);
    }

    void skipBlock() {
        final int len = readU32();
        if (pos > (buffer.length - len)) {
            throw new IllegalArgumentException("not enough data for block");
        }

        pos += len;
    }

    byte[] readPaddedBlock() {
        return readPaddedBlock(8);
    }

    byte[] readPaddedBlock(int blockSize) {
        final int len = readU32();
        if (len == 0) {
            return new byte[0];
        }

        if (pos > (buffer.length - len)) {
            throw new IllegalArgumentException("not enough data for block");
        }

        final int align = len % blockSize;
        if (0 != align) {
            throw new IllegalArgumentException("missing padding");
        }

        final int start = pos;
        pos += len;
        int end = pos;

        if (len > 0) {
            final int lastByte = buffer[pos - 1] & 0xFF;
            if (0 < lastByte && lastByte < blockSize) {
                end -= lastByte;

                for (int i = 1, padPos = end; i <= lastByte; ++i, ++padPos) {
                    if (i != (buffer[padPos] & 0xFF)) {
                        throw new IllegalArgumentException("incorrect padding");
                    }
                }
            }
        }

        return Arrays.copyOfRange(buffer, start, end);
    }
}
