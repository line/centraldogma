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

package com.linecorp.centraldogma.server.mirror.credential;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import com.google.common.io.BaseEncoding;

final class MirrorCredentialUtil {
    private static final String BASE64_PREFIX = "base64:";

    private static final BaseEncoding base64 = BaseEncoding.base64();

    static byte[] decodeBase64(String value, String name) {
        requireNonNull(value, name);
        return base64.decode(value);
    }

    static byte[] decodeBase64OrUtf8(@Nullable String value, String name) {
        if (value == null) {
            return null;
        } else if (value.startsWith(BASE64_PREFIX)) {
            return decodeBase64(value.substring(BASE64_PREFIX.length()), name);
        } else {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    static String requireNonEmpty(String value, String name) {
        requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is empty.");
        }
        return value;
    }

    static byte[] requireNonEmpty(byte[] value, String name) {
        requireNonNull(value, name);
        if (value.length == 0) {
            throw new IllegalArgumentException(name + " is empty.");
        }
        return value;
    }

    private MirrorCredentialUtil() {}
}
