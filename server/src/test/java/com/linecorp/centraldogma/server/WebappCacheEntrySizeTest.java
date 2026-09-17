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
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
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

        final List<String> oversizedAssets =
                findOversizedAssets(resourceRoot, CentralDogma.WEBAPP_MAX_CACHE_ENTRY_SIZE_BYTES);

        assertThat(oversizedAssets)
                .as("pre-compressed webapp assets must fit in the %,d-byte FileService cache entry",
                    CentralDogma.WEBAPP_MAX_CACHE_ENTRY_SIZE_BYTES)
                .isEmpty();
    }

    private static List<String> findOversizedAssets(URL resourceRoot, long maxSizeBytes) throws Exception {
        if ("file".equals(resourceRoot.getProtocol())) {
            return findOversizedAssets(Path.of(resourceRoot.toURI()), maxSizeBytes);
        }
        if ("jar".equals(resourceRoot.getProtocol())) {
            return findOversizedAssetsInJar(resourceRoot, maxSizeBytes);
        }
        throw new IllegalArgumentException("unsupported webapp resource protocol: " + resourceRoot);
    }

    private static List<String> findOversizedAssets(Path root, long maxSizeBytes) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                        .filter(WebappCacheEntrySizeTest::isPrecompressedAsset)
                        .filter(path -> size(path) > maxSizeBytes)
                        .map(path -> root.relativize(path) + " (" + size(path) + " bytes)")
                        .collect(Collectors.toList());
        }
    }

    private static List<String> findOversizedAssetsInJar(URL resourceRoot, long maxSizeBytes)
            throws IOException {
        final JarURLConnection connection = (JarURLConnection) resourceRoot.openConnection();
        connection.setUseCaches(false);
        final String root = connection.getEntryName();
        if (root == null) {
            throw new IllegalArgumentException("webapp resource root is missing from URL: " + resourceRoot);
        }

        try (JarFile jar = connection.getJarFile()) {
            final String resourcePrefix = root + '/';
            return jar.stream()
                      .filter(entry -> !entry.isDirectory())
                      .filter(entry -> entry.getName().startsWith(resourcePrefix))
                      .filter(entry -> isPrecompressedAsset(entry.getName()))
                      .filter(entry -> entry.getSize() > maxSizeBytes)
                      .map(entry -> formatAssetSize(entry.getName().substring(resourcePrefix.length()),
                                                     entry.getSize()))
                      .collect(Collectors.toList());
        }
    }

    private static boolean isPrecompressedAsset(Path path) {
        return isPrecompressedAsset(path.getFileName().toString());
    }

    private static boolean isPrecompressedAsset(String name) {
        return name.endsWith(".br") || name.endsWith(".gz");
    }

    private static String formatAssetSize(String path, long size) {
        return path + " (" + size + " bytes)";
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read webapp asset size: " + path, e);
        }
    }
}
