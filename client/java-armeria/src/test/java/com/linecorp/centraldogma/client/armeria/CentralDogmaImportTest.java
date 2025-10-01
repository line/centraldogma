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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.centraldogma.client.armeria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class CentralDogmaImportTest {

    @RegisterExtension
    static CentralDogmaExtension dogmaExt = new CentralDogmaExtension() {
    };

    CentralDogma dogma;
    String project;
    String repository;
    Random random;
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws UnknownHostException {
        random = new Random();
        project = "foo" + random.nextInt(1000);
        repository = "bar";
        dogma = new ArmeriaCentralDogmaBuilder()
                .host(dogmaExt.serverAddress().getHostString(),
                      dogmaExt.serverAddress().getPort())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        dogma.close();
    }

    @Test
    void importDir_shouldOnlyCreateProjectAndRepo_whenNoFiles() throws Exception {
        // given
        final Path fooBar = tempDir.resolve(project).resolve(repository);
        Files.createDirectories(fooBar);

        // when
        final PushResult result = dogma.importDir(project, repository, fooBar, true).join();
        final Map<String, EntryType> entries = dogma.listFiles(
                project, repository, Revision.HEAD, PathPattern.of("/**")).join();
        final List<String> importedPaths = entries.entrySet().stream()
                                                  .filter(e -> e.getValue() == EntryType.TEXT)
                                                  .map(Map.Entry::getKey)
                                                  .collect(Collectors.toList());

        // then
        assertThat(result).isNull();
        assertThat(dogma.listProjects().join()).contains(project);
        assertThat(dogma.listRepositories(project).join()).containsKey(repository);
        assertThat(importedPaths).isEmpty();
    }

    @Test
    void importDir_shouldIgnoresPlaceholders() throws Exception {
        // given
        final Path fooBar = tempDir.resolve(project).resolve(repository);
        Files.createDirectories(fooBar);
        final Path boundaryFile = fooBar.resolve("hello.txt");

        Files.createDirectories(fooBar);
        Files.write(fooBar.resolve(".gitkeep"), new byte[0]);
        Files.write(fooBar.resolve(".gitignore"), new byte[0]);
        Files.write(boundaryFile, "hello".getBytes(StandardCharsets.UTF_8));

        // when
        final PushResult result = dogma.importDir(project, repository, fooBar, true).join();
        final Map<String, EntryType> entries = dogma.listFiles(
                project, repository, Revision.HEAD, PathPattern.of("/**")).join();
        final List<String> importedPaths = entries.entrySet().stream()
                                                  .filter(e -> e.getValue() == EntryType.TEXT)
                                                  .map(Map.Entry::getKey)
                                                  .collect(Collectors.toList());

        // then
        assertThat(result).isNotNull();
        assertThat(result.revision().major()).isPositive();
        assertThat(importedPaths).contains("/hello.txt");
        assertThat(importedPaths).doesNotContain("/.gitkeep", "/.gitignore");
    }

    @Test
    void importDir_createsProjectRepo_andImportsFile() throws IOException {
        // given
        final String fileName = "hello.txt";
        final Path dirPath = tempDir.resolve(project).resolve(repository);
        Files.createDirectories(dirPath);
        final Path file = dirPath.resolve(fileName);
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        // when
        final PushResult pushResult = dogma.importDir(project, repository, dirPath, true).join();

        // then
        assertThat(pushResult).isNotNull();
        assertThat(pushResult.revision().major()).isPositive();
        assertThat(pushResult.revision().major()).isPositive();

        final Change<?> diff = dogma.forRepo(project, repository)
                                    .diff(Query.ofText('/' + fileName))
                                    .get(Revision.INIT, Revision.HEAD)
                                    .join();
        assertThat(diff.path()).isEqualTo('/' + fileName);
        assertThat(diff.contentAsText()).isEqualTo("hello" + '\n');
    }

    @Test
    void test_importDir_createsProjectRepo_andImportsFile_nonExistentDirectory() {
        // given
        final Path nonExistentDir = tempDir.resolve("non_existent_directory");

        // when / then
        assertThatThrownBy(() ->
                                   dogma.importDir(project, repository, nonExistentDir, true).join())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void importDir_createsProjectRepo_andPushes() throws Exception {
        // given
        final Path fooBar = tempDir.resolve(project).resolve(repository);
        Files.createDirectories(fooBar);
        final Path file = fooBar.resolve("hello.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        // when
        final PushResult result = dogma.importDir(project, null, fooBar, true).join();
        final Map<String, EntryType> entries = dogma.listFiles(
                project, repository, Revision.HEAD, PathPattern.of("/**")).join();
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
    void importDir_shouldUsePathComponentsAsProjectAndRepo_whenProjectAndRepoAreNull() throws Exception {
        // given
        final Path pathBased = tempDir.resolve(project).resolve(repository);
        Files.createDirectories(pathBased);
        final Path file = pathBased.resolve("hello.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        // when
        final PushResult result = dogma.importDir(null, null, pathBased, true).join();

        // then
        assertThat(result).isNotNull();
        assertThat(result.revision().major()).isPositive();

        final Map<String, EntryType> entries = dogma.listFiles(
                project, repository, Revision.HEAD, PathPattern.of("/**")).join();
        final List<String> importedPaths = entries.entrySet().stream()
                                                  .filter(e -> e.getValue() == EntryType.TEXT)
                                                  .map(Map.Entry::getKey)
                                                  .collect(Collectors.toList());
        assertThat(importedPaths).contains("/hello.txt");
    }
}

