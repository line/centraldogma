/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.centraldogma.client.armeria;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.ImportResult;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

class CentralDogmaImportTest {

    ScheduledExecutorService scheduler;
    Random random;
    String project;
    String repository;
    String dir;
    CentralDogma dogma;

    private static void removeCreatedDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> {
                     try {
                         Files.deleteIfExists(p);
                     } catch (IOException ignored) {
                     }
                 });
        }
        final Path projectRoot = dir.getParent();
        if (projectRoot != null && Files.exists(projectRoot)) {
            try {
                Files.deleteIfExists(projectRoot);
            } catch (IOException ignored) {
            }
        }
    }

    private static void cleanProjectAndRepo(CentralDogma centralDogma, String project, String repository)
            throws IOException {
        centralDogma.removeRepository(project, repository).exceptionally(ex -> null);
        centralDogma.purgeRepository(project, repository).exceptionally(ex -> null);

        centralDogma.removeProject(project).exceptionally(ex -> null);
        centralDogma.purgeProject(project).exceptionally(ex -> null);
    }

    @BeforeEach
    void setUp() throws IOException {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        random = new Random();
        project = "foo" + random.nextInt(1000);
        repository = "bar";
        dir = project + '/' + repository;
        dogma = new ArmeriaCentralDogmaBuilder()
                .host("localhost", 36462)
                .build();
        cleanProjectAndRepo(dogma, project, repository);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            cleanProjectAndRepo(dogma, project, repository);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            dogma.close();
        }
        scheduler.shutdownNow();
        removeCreatedDir(Paths.get(dir));
        dogma.close();
    }

    @Test
    void importDir_createsProjectRepo_andPushes() throws Exception {
        // given
        final Path fooBar = Paths.get(dir);
        Files.createDirectories(fooBar);
        final Path file = fooBar.resolve("hello.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        // when
        final ImportResult result = dogma.importDir(fooBar).join();

        final Map<String, EntryType> entries = dogma.listFiles(
                project, repository, Revision.HEAD,
                PathPattern.of("/**")).join();
        final List<String> importedPaths = entries.entrySet().stream()
                                                  .filter(e -> e.getValue() == EntryType.TEXT)
                                                  .map(Map.Entry::getKey)
                                                  .collect(Collectors.toList());
        // then
        assertThat(result).isNotNull();
        assertThat(result.revision().major()).isPositive();
        assertThat(importedPaths).contains("/hello.txt");
    }

    @Test
    void importDir_shouldIgnoresPlaceholders() throws Exception {
        // given
        final Path fooBar = Paths.get(dir);
        Files.createDirectories(fooBar);
        final Path boundaryFile = fooBar.resolve("hello.txt");

        // setUp Test Directory
        Files.createDirectories(fooBar);
        Files.write(fooBar.resolve(".gitkeep"), "".getBytes(StandardCharsets.UTF_8));
        Files.write(fooBar.resolve(".gitignore"), "".getBytes(StandardCharsets.UTF_8));
        Files.write(boundaryFile, "hello".getBytes(StandardCharsets.UTF_8));

        // when
        final ImportResult result = dogma.importDir(fooBar).join();
        final Map<String, EntryType> entries = dogma.listFiles(
                project, repository, Revision.HEAD,
                PathPattern.of("/**")).join();

        final List<String> importedPaths = entries.entrySet().stream()
                                                  .filter(e -> e.getValue() == EntryType.TEXT)
                                                  .map(Map.Entry::getKey)
                                                  .collect(Collectors.toList());

        // then
        // ImportDir() should ignore placeholders such as .gitkeep and .gitignore
        assertThat(result).isNotNull();
        assertThat(result.revision().major()).isPositive();
        assertThat(importedPaths).contains("/hello.txt");
        assertThat(importedPaths).doesNotContain("/.gitkeep", "/.gitignore");
    }

    @Test
    void importDir_onlyCreateRepositoryWhenFilesNotExists() throws IOException {
        // given
        final Path fooBar = Paths.get(dir);
        Files.createDirectories(fooBar);
        Files.write(fooBar.resolve("hello.txt"), "hello".getBytes(UTF_8));

        // when
        final ImportResult result = dogma.importDir(fooBar).join();

        // then
        assertThat(result).isNotNull();
        assertThat(result.revision().major()).isPositive();
    }

    @Test
    void importDir_createsProjectRepo_andImportsFile() throws IOException {
        final String fileName = "hello.txt";
        final Path dirPath = Paths.get(dir);
        Files.createDirectories(dirPath);
        final Path file = dirPath.resolve(fileName);
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

            final ImportResult importResult = dogma.importDir(
                    Paths.get(project + '/' + repository + '/' + fileName)).join();
            assertThat(importResult).isNotNull();
            assertThat(importResult.revision().major()).isPositive();
            assertThat(importResult.isEmpty()).isFalse();

            final Change<?> diff = dogma.forRepo(project, repository)
                                               .diff(Query.ofText('/' + fileName))
                                               .get(Revision.INIT, Revision.HEAD)
                                               .join();
            assertThat(diff.path()).isEqualTo('/' + fileName);
            assertThat(diff.contentAsText()).isEqualTo("hello" + '\n');
    }

    @Test
    void importResourceDir_createsAndPushes() throws IOException {
        // given
        final String baseDir = "resource-test";
        final Path baseDirPath = Paths.get(baseDir);
        Files.createDirectories(baseDirPath);

        final Path resourceDir = baseDirPath.resolve(dir);
        Files.createDirectories(resourceDir);
        Files.write(resourceDir.resolve("hello.txt"), "hello".getBytes(UTF_8));

        try (URLClassLoader cl = new URLClassLoader(new URL[] { baseDirPath.toUri().toURL() },
                                                    Thread.currentThread().getContextClassLoader())) {

            // when
            final ImportResult importResult = dogma.importResourceDir(dir, cl).join();

            // then
            final Map<String, EntryType> entries = dogma.listFiles(
                    project, repository, Revision.HEAD,
                    PathPattern.of("/**")).join();
            final List<String> importedPaths = entries.entrySet().stream()
                                                      .filter(e -> e.getValue() == EntryType.TEXT)
                                                      .map(Map.Entry::getKey)
                                                      .collect(Collectors.toList());
            assertThat(importResult).isNotNull();
            assertThat(importResult.revision().major()).isPositive();
            assertThat(importedPaths).contains("/hello.txt");
        } finally {
            removeCreatedDir(baseDirPath);
        }
    }

    @Test
    void importResourceDir_createsProjectRepo_andImportsFile() throws IOException {
        // given
        final String fileName = "hello.txt";
        final String content = "world";

        final Path tempRoot = Files.createTempDirectory("cp-");
        final Path fooBar = tempRoot.resolve(project).resolve(repository);
        Files.createDirectories(fooBar);
        Files.write(fooBar.resolve(fileName), content.getBytes(UTF_8));

        try (URLClassLoader cl = new URLClassLoader(new URL[] { tempRoot.toUri().toURL() },
                                                    Thread.currentThread().getContextClassLoader())) {

            // when
            final ImportResult result = dogma.importResourceDir(project + '/' + repository, cl)
                                                    .join();

            // then
            assertThat(result).isNotNull();
            assertThat(result.revision().major()).isPositive();
            assertThat(result.isEmpty()).isFalse();

            final Change<?> diff = dogma.forRepo(project, repository)
                                               .diff(Query.ofText('/' + fileName))
                                               .get(Revision.INIT, Revision.HEAD)
                                               .join();
            assertThat(diff.path()).isEqualTo('/' + fileName);
            assertThat(diff.content()).isEqualTo(content + '\n');
        }
    }

    @Test
    void importResourceDir_createsProjectRepo_andImportsFileWithString() throws IOException {
        // given
        final String fileName = "hello.txt";
        final String content = "world";

        // baseDir will be added to a dedicated URLClassLoader
        final Path baseDir = Files.createTempDirectory("cp-");
        final Path repoDir = baseDir.resolve(project).resolve(repository);
        Files.createDirectories(repoDir);
        Files.write(repoDir.resolve(fileName), content.getBytes(UTF_8));

        final ClassLoader previousCl = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader cl = new URLClassLoader(
                new URL[] { baseDir.toUri().toURL() },
                previousCl)) {

            Thread.currentThread().setContextClassLoader(cl);

            // when
            final ImportResult result = dogma.importResourceDir(project + '/' + repository)
                                                    .join();

            // then
            assertThat(result).isNotNull();
            assertThat(result.revision().major()).isPositive();
            assertThat(result.isEmpty()).isFalse();

            final Change<?> diff = dogma.forRepo(project, repository)
                                               .diff(Query.ofText('/' + fileName))
                                               .get(Revision.INIT, Revision.HEAD)
                                               .join();
            assertThat(diff.path()).isEqualTo('/' + fileName);
            assertThat(diff.content()).isEqualTo(content + '\n');
        } finally {
            // Restore original context ClassLoader
            Thread.currentThread().setContextClassLoader(previousCl);
        }
    }
}
