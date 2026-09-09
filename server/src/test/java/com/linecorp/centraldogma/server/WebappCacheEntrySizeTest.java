/*
 * Copyright 2026 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class WebappCacheEntrySizeTest {

    private static final String WEBAPP_RESOURCE_ROOT = "com/linecorp/centraldogma/webapp";

    @Test
    void precompressedAssetsFitInTheFileServiceCache() throws Exception {
        final URL resourceRoot =
                WebappCacheEntrySizeTest.class.getClassLoader().getResource(WEBAPP_RESOURCE_ROOT);
        assertThat(resourceRoot).as("webapp resource root").isNotNull();
        assertThat(resourceRoot.getProtocol()).isEqualTo("file");

        final Path root = Path.of(resourceRoot.toURI());
        final List<String> oversizedAssets;
        try (Stream<Path> paths = Files.walk(root)) {
            oversizedAssets = paths.filter(Files::isRegularFile)
                                   .filter(WebappCacheEntrySizeTest::isPrecompressedAsset)
                                   .filter(path -> size(path) > CentralDogma.WEBAPP_MAX_CACHE_ENTRY_SIZE_BYTES)
                                   .map(path -> root.relativize(path) + " (" + size(path) + " bytes)")
                                   .collect(Collectors.toList());
        }

        assertThat(oversizedAssets)
                .as("pre-compressed webapp assets must fit in the %,d-byte FileService cache entry",
                    CentralDogma.WEBAPP_MAX_CACHE_ENTRY_SIZE_BYTES)
                .isEmpty();
    }

    private static boolean isPrecompressedAsset(Path path) {
        final String name = path.getFileName().toString();
        return name.endsWith(".br") || name.endsWith(".gz");
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read webapp asset size: " + path, e);
        }
    }
}
