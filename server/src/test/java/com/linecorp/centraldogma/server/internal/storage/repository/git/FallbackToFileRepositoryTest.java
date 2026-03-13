/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.RocksDbRepository;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

class FallbackToFileRepositoryTest {

    private static final String PROJECT_NAME = "testProject";
    private static final String REPO_NAME = "testRepo";

    @TempDir
    private File rootDir;

    private EncryptionStorageManager encryptionStorageManager;
    private GitRepositoryManager gitRepositoryManager;
    private Project project;

    @BeforeEach
    void setUp() {
        project = mock(Project.class);
        lenient().when(project.name()).thenReturn(PROJECT_NAME);

        encryptionStorageManager = EncryptionStorageManager.of(
                new File(rootDir, "rocksdb").toPath(), false, "kekId");
        gitRepositoryManager = new GitRepositoryManager(
                project, new File(rootDir, PROJECT_NAME), commonPool(),
                MoreExecutors.directExecutor(), null, encryptionStorageManager);
    }

    @AfterEach
    void tearDown() {
        if (encryptionStorageManager != null) {
            encryptionStorageManager.close();
        }
    }

    @Test
    void fallbackRestoredContent() throws Exception {
        // Create a file-based repository and add some commits before migration.
        final Repository fileRepo = gitRepositoryManager.create(REPO_NAME, 0, Author.SYSTEM, false);
        assertThat(fileRepo.isEncrypted()).isFalse();

        fileRepo.commit(Revision.INIT, 0, Author.SYSTEM, "Add foo",
                        ImmutableList.of(Change.ofJsonUpsert("/foo.json", "{\"a\":\"b\"}"))).join();
        fileRepo.commit(new Revision(2), 0, Author.SYSTEM, "Add bar",
                        ImmutableList.of(Change.ofTextUpsert("/bar.txt", "hello"))).join();

        final Revision headBeforeMigration = fileRepo.normalizeNow(Revision.HEAD);
        assertThat(headBeforeMigration.major()).isEqualTo(3);

        // Migrate to encrypted.
        storeWdekAndMigrate(REPO_NAME);
        assertThat(gitRepositoryManager.get(REPO_NAME).isEncrypted()).isTrue();

        // Fall back to file-based. The original git files are still in repoDir
        // (migrateToEncryptedRepository preserves them), so fallback simply deletes
        // the placeholder and reopens the pre-migration file-based repository.
        gitRepositoryManager.fallbackToFileRepository(REPO_NAME);

        final Repository restoredRepo = gitRepositoryManager.get(REPO_NAME);
        assertThat(restoredRepo.isEncrypted()).isFalse();
        assertThat(restoredRepo.jGitRepository()).isNotInstanceOf(RocksDbRepository.class);

        // The restored repo is at the pre-migration state.
        assertThat(restoredRepo.normalizeNow(Revision.HEAD)).isEqualTo(headBeforeMigration);

        final Entry<?> fooEntry = restoredRepo.get(Revision.HEAD, Query.ofJson("/foo.json")).join();
        final JsonNode fooJson = Jackson.readTree(fooEntry.contentAsText());
        assertThat(fooJson.get("a").asText()).isEqualTo("b");

        final Entry<?> barEntry = restoredRepo.get(Revision.HEAD, Query.ofText("/bar.txt")).join();
        assertThat(barEntry.contentAsText().trim()).isEqualTo("hello");
    }

    @Test
    void fallbackEncryptionDataCleanedUp() {
        gitRepositoryManager.create(REPO_NAME, 0, Author.SYSTEM, false);
        storeWdekAndMigrate(REPO_NAME);

        // Encryption data must exist before fallback.
        final Map<String, Map<String, byte[]>> dataBeforeFallback = encryptionStorageManager.getAllData();
        assertThat(dataBeforeFallback.get("wdek")).isNotEmpty();
        assertThat(dataBeforeFallback.get("encrypted_object")).isNotEmpty();

        gitRepositoryManager.fallbackToFileRepository(REPO_NAME);

        // All encryption data for this repo is deleted after fallback.
        final Map<String, Map<String, byte[]>> dataAfterFallback = encryptionStorageManager.getAllData();
        assertThat(dataAfterFallback.get("wdek")).isEmpty();
        assertThat(dataAfterFallback.get("encryption_metadata")).isEmpty();
        assertThat(dataAfterFallback.get("encrypted_object_id")).isEmpty();
        assertThat(dataAfterFallback.get("encrypted_object")).isEmpty();
    }

    @Test
    void fallbackPlaceholderFileIsRemoved() {
        final Repository fileRepo = gitRepositoryManager.create(REPO_NAME, 0, Author.SYSTEM, false);
        final File repoDir = fileRepo.repoDir();

        storeWdekAndMigrate(REPO_NAME);
        assertThat(Files.exists(Paths.get(repoDir.getPath(), ".encryption-repo-placeholder"))).isTrue();

        gitRepositoryManager.fallbackToFileRepository(REPO_NAME);
        assertThat(Files.exists(Paths.get(repoDir.getPath(), ".encryption-repo-placeholder"))).isFalse();
    }

    @Test
    void fallbackRepoIsUsableAfterFallback() {
        // Migrate and fall back; then verify new commits can be pushed to the restored repo.
        gitRepositoryManager.create(REPO_NAME, 0, Author.SYSTEM, false);
        storeWdekAndMigrate(REPO_NAME);
        gitRepositoryManager.fallbackToFileRepository(REPO_NAME);

        final Repository repo = gitRepositoryManager.get(REPO_NAME);
        assertThat(repo.isEncrypted()).isFalse();

        final Revision headBefore = repo.normalizeNow(Revision.HEAD);
        repo.commit(headBefore, 0, Author.SYSTEM, "Post-fallback commit",
                    ImmutableList.of(Change.ofTextUpsert("/new.txt", "world"))).join();

        assertThat(repo.normalizeNow(Revision.HEAD).major()).isEqualTo(headBefore.major() + 1);
        assertThat(repo.get(Revision.HEAD, Query.ofText("/new.txt")).join()
                       .contentAsText().trim()).isEqualTo("world");
    }

    @Test
    void fallbackPreservesPreMigrationCommitHistory() {
        // Commits added before migration are visible in the restored file-based repo.
        final Repository fileRepo = gitRepositoryManager.create(REPO_NAME, 0, Author.SYSTEM, false);

        fileRepo.commit(Revision.INIT, 0, Author.SYSTEM, "First commit",
                        ImmutableList.of(Change.ofTextUpsert("/first.txt", "first"))).join();
        fileRepo.commit(new Revision(2), 0, Author.SYSTEM, "Second commit",
                        ImmutableList.of(Change.ofTextUpsert("/second.txt", "second"))).join();
        fileRepo.commit(new Revision(3), 0, Author.SYSTEM, "Third commit",
                        ImmutableList.of(Change.ofTextUpsert("/third.txt", "third"))).join();

        storeWdekAndMigrate(REPO_NAME);
        gitRepositoryManager.fallbackToFileRepository(REPO_NAME);

        final Repository restoredRepo = gitRepositoryManager.get(REPO_NAME);
        assertThat(restoredRepo.normalizeNow(Revision.HEAD).major()).isEqualTo(4);

        // history() returns commits in ascending order (oldest first).
        final List<Commit> history = restoredRepo.history(new Revision(2), Revision.HEAD, "/**").join();
        assertThat(history).hasSize(3);
        assertThat(history.get(0).summary()).isEqualTo("First commit");
        assertThat(history.get(1).summary()).isEqualTo("Second commit");
        assertThat(history.get(2).summary()).isEqualTo("Third commit");
    }

    @Test
    void fallbackRepoReloadsCorrectlyAfterManagerRestart() {
        // After fallback, a manager restart should load the repo as file-based (no placeholder).
        gitRepositoryManager.create(REPO_NAME, 0, Author.SYSTEM, false);
        storeWdekAndMigrate(REPO_NAME);
        gitRepositoryManager.fallbackToFileRepository(REPO_NAME);
        gitRepositoryManager.close(() -> null);

        final GitRepositoryManager reloadedManager = new GitRepositoryManager(
                project, new File(rootDir, PROJECT_NAME), commonPool(),
                MoreExecutors.directExecutor(), null, encryptionStorageManager);

        final Repository reloadedRepo = reloadedManager.get(REPO_NAME);
        assertThat(reloadedRepo.isEncrypted()).isFalse();
        assertThat(reloadedRepo.jGitRepository()).isNotInstanceOf(RocksDbRepository.class);
    }

    @Test
    void fallbackPreservesOtherRepoEncryptionData() {
        // Falling back one repo must not affect another encrypted repo.
        final String otherRepo = "otherRepo";

        gitRepositoryManager.create(REPO_NAME, 0, Author.SYSTEM, false);
        storeWdekAndMigrate(REPO_NAME);

        final String wdek2 = encryptionStorageManager.generateWdek().join();
        encryptionStorageManager.storeWdek(
                new WrappedDekDetails(wdek2, 1, encryptionStorageManager.kekId(),
                                     PROJECT_NAME, otherRepo));
        gitRepositoryManager.create(otherRepo, 0, Author.SYSTEM, true);

        assertThat(gitRepositoryManager.get(REPO_NAME).isEncrypted()).isTrue();
        assertThat(gitRepositoryManager.get(otherRepo).isEncrypted()).isTrue();

        gitRepositoryManager.fallbackToFileRepository(REPO_NAME);

        assertThat(gitRepositoryManager.get(REPO_NAME).isEncrypted()).isFalse();
        assertThat(gitRepositoryManager.get(otherRepo).isEncrypted()).isTrue();

        // Encryption data for otherRepo must still be present.
        final Map<String, Map<String, byte[]>> allData = encryptionStorageManager.getAllData();
        assertThat(allData.get("wdek")).isNotEmpty();
        assertThat(allData.get("encrypted_object")).isNotEmpty();
    }

    private void storeWdekAndMigrate(String repoName) {
        final String wdek = encryptionStorageManager.generateWdek().join();
        encryptionStorageManager.storeWdek(
                new WrappedDekDetails(wdek, 1, encryptionStorageManager.kekId(),
                                     PROJECT_NAME, repoName));
        gitRepositoryManager.migrateToEncryptedRepository(repoName);
    }
}
