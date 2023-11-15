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
package com.linecorp.centraldogma.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

import com.google.common.collect.ImmutableList;

enum DefaultConfigValueConverter implements ConfigValueConverter {
    INSTANCE;

    private static final String PLAINTEXT = "plaintext";
    private static final String FILE = "file";
    private static final String BASE64 = "base64";

    // TODO(minwoox): Add more prefixes such as classpath, url, etc.
    private static final List<String> SUPPORTED_PREFIXES = ImmutableList.of(PLAINTEXT, FILE, BASE64);

    @Override
    public List<String> supportedPrefixes() {
        return SUPPORTED_PREFIXES;
    }

    @Override
    public String convert(String prefix, String value) {
        switch (prefix) {
            case PLAINTEXT:
                return value;
            case FILE:
                try {
                    return new String(Files.readAllBytes(Paths.get(value)), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RuntimeException("failed to read a file: " + value, e);
                }
            case BASE64:
                return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8).trim();
            default:
                // Should never reach here.
                throw new Error();
        }
    }
}
