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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
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
        removeCreatedDir(Paths.get(dir).toAbsolutePath());
        removeCreatedDir(Paths.get(project).toAbsolutePath());
        removeCreatedDir(Paths.get(project));
        removeCreatedDir(Paths.get(dir));
    }

    /**
     * Tests that importDir only creates project and repository when no files exist in the directory.
     * This test verifies that the method creates the project and repository structures
     * but does not import any files when the specified directory is empty.
     */
    @Test
    void importDir_shouldOnlyCreateProjectAndRepo_whenNoFiles() throws Exception {
        // given
        final Path fooBar = Paths.get(dir).toAbsolutePath();
        Files.createDirectories(fooBar);

        // when
        final PushResult result = dogma.importDir(project, repository, fooBar, true).join();
        final Map<String, EntryType> entries = dogma.listFiles(
                project, repository, Revision.HEAD,
                PathPattern.of("/**")).join();

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

    /**
     * Tests that importDir ignores placeholder files (.gitkeep and .gitignore) when
     * importing a directory with both project name and repository name as null.
     * Verifies that only non-placeholder files are imported successfully.
     */
    @Test
    void importDir_shouldIgnoresPlaceholders() throws Exception {
        // given
        final Path fooBar = Paths.get(dir).toAbsolutePath();
        Files.createDirectories(fooBar);
        final Path boundaryFile = fooBar.resolve("hello.txt").toAbsolutePath();

        // setUp Test Directory
        Files.createDirectories(fooBar);
        Files.write(fooBar.resolve(".gitkeep"), "".getBytes(StandardCharsets.UTF_8));
        Files.write(fooBar.resolve(".gitignore"), "".getBytes(StandardCharsets.UTF_8));
        Files.write(boundaryFile, "hello".getBytes(StandardCharsets.UTF_8));

        // when
        final PushResult result = dogma.importDir(project, repository, fooBar, true).join();
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

    /**
     * Tests that importDir creates a project repository and imports a file successfully.
     * This test verifies the creation of the project, repository, and the successful import of a file,
     * as well as the correct content and path of the imported file.
     */
    @Test
    void importDir_createsProjectRepo_andImportsFile() throws IOException {
        final String fileName = "hello.txt";
        final Path dirPath = Paths.get(dir).toAbsolutePath();
        Files.createDirectories(dirPath);
        final Path file = dirPath.resolve(fileName).toAbsolutePath();
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        final PushResult pushResult = dogma.importDir(project, repository, dirPath, true).join();
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

    /**
     * Tests the behavior of importDir when the directory to be imported does not exist.
     * This test verifies that the method handles the case of a non-existent directory correctly.
     */
    @Test
    public void test_importDir_createsProjectRepo_andImportsFile_nonExistentDirectory() throws IOException {
        final CentralDogmaImportTest testInstance = new CentralDogmaImportTest();
        testInstance.setUp();

        final Path nonExistentDir = Paths.get("non_existent_directory");
        assertThrows(CompletionException.class, () -> {
            testInstance.dogma.importDir(testInstance.project, testInstance.repository, nonExistentDir, true)
                              .join();
        });
    }

    /**
     * Tests that importDir creates a project repository and pushes files when project name is provided but repository name is null.
     * This test verifies the creation of the project, a default repository, and the successful import of a file.
     */
    @Test
    public void importDir_createsProjectRepo_andPushes() throws Exception {
        // given
        final Path fooBar = Paths.get(dir).toAbsolutePath();
        Files.createDirectories(fooBar);
        final Path file = fooBar.resolve("hello.txt").toAbsolutePath();
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        // when
        final PushResult result = dogma.importDir(project, null, fooBar, true).join();

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
    void importDir_shouldUsePathComponentsAsProjectAndRepo_whenProjectAndRepoAreNull() throws Exception {
        // given
        final Path pathBased = Paths.get(dir).toAbsolutePath();
        Files.createDirectories(pathBased);
        final Path file = pathBased.resolve("hello.txt");
        Files.write(file, "hello".getBytes(UTF_8));

        // when
        final PushResult result = dogma.importDir(null, null, pathBased, true).join();

        // then
        assertThat(result).isNotNull();
        assertThat(result.revision().major()).isPositive();

        final Map<String, EntryType> entries = dogma.listFiles(
                project, repository, Revision.HEAD,
                PathPattern.of("/**")).join();

        final List<String> importedPaths = entries.entrySet().stream()
                                                  .filter(e -> e.getValue() == EntryType.TEXT)
                                                  .map(Map.Entry::getKey)
                                                  .collect(Collectors.toList());

        assertThat(importedPaths).contains("/hello.txt");
    }
}
