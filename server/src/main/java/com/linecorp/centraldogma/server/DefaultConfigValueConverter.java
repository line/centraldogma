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
import java.util.List;

import com.google.common.collect.ImmutableList;

enum DefaultConfigValueConverter implements ConfigValueConverter {
    INSTANCE;

    private static final String FILE = "file";

    // TODO(minwoox): Add more prefixes such as classpath, url, etc.
    private static final List<String> SUPPORTED_PREFIX = ImmutableList.of(FILE);

    @Override
    public List<String> supportedPrefixes() {
        return SUPPORTED_PREFIX;
    }

    @Override
    public String convert(String prefix, String value) {
        if (!FILE.equals(prefix)) {
            throw new IllegalArgumentException("unsupported prefix: " + prefix + ", value: " + value);
        }

        try {
            return new String(Files.readAllBytes(Paths.get(value)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("failed to read a file: " + value, e);
        }
    }
}
