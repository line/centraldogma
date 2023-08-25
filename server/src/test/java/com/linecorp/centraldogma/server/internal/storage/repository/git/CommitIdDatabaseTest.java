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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.project.Project;

class CommitIdDatabaseTest {

    @TempDir
    File tempDir;

    private CommitIdDatabase db;

    @BeforeEach
    void setUp() {
        db = new CommitIdDatabase(tempDir);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void emptyDatabase() {
        assertThat(db.headRevision()).isNull();
        assertThatThrownBy(() -> db.get(Revision.INIT)).isInstanceOf(IllegalStateException.class);
    }

    @ValueSource(ints = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 })
    @ParameterizedTest
    void simpleAccess(int startRevision) {
        final int numCommits = 10;
        final ObjectId[] expectedCommitIds = new ObjectId[numCommits + startRevision];
        for (int i = startRevision; i < startRevision + numCommits; i++) {
            final Revision revision = new Revision(i);
            final ObjectId commitId = randomCommitId();
            expectedCommitIds[i] = commitId;
            db.put(revision, commitId);
            assertThat(db.headRevision()).isEqualTo(revision);
        }

        for (int i = startRevision; i < startRevision + numCommits; i++) {
            assertThat(db.get(new Revision(i))).isEqualTo(expectedCommitIds[i]);
        }

        assertThatThrownBy(() -> db.get(Revision.HEAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute revision");
        assertThatThrownBy(() -> db.get(new Revision(numCommits + startRevision)))
                .isInstanceOf(RevisionNotFoundException.class);
        if (startRevision > 1) {
            assertThatThrownBy(() -> db.get(new Revision(startRevision - 1)))
                    .isInstanceOf(RevisionNotFoundException.class);
        }
        assertThat(db.firstRevision()).isEqualTo(new Revision(startRevision));
    }

    @Test
    void truncatedDatabase() throws Exception {
        db.put(Revision.INIT, randomCommitId());
        db.close();

        // Truncate the database file.
        try (FileChannel f = FileChannel.open(new File(tempDir, "commit_ids.dat").toPath(),
                                              StandardOpenOption.APPEND)) {

            assertThat(f.size()).isEqualTo(24);
            f.truncate(23);
        }

        assertThatThrownBy(() -> new CommitIdDatabase(tempDir))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("incorrect file length");
    }

    @ValueSource(ints = { 1, 3, 5, 7, 9 })
    @ParameterizedTest
    void rebuildingBadDatabase(int startRevision) throws Exception {
        final int numCommits = 10;
        final File repoDir = tempDir;

        GitRepositoryV2 repo = createRepoWithFirstRevision(repoDir, startRevision);
        Revision headRevision = null;
        try {
            // We already add 2 commits in createRepoWithFirstRevision so let's start with startRevision + 2.
            for (int i = startRevision + 2; i < startRevision + numCommits; i++) {
                headRevision = addCommit(repo, i);
            }
        } finally {
            repo.internalClose();
        }

        final File primaryRepoDir = repo.primaryRepo.repoDir();
        final File commitIdDatabaseFile = new File(primaryRepoDir, "commit_ids.dat");
        // Wipe out the commit ID database.
        assertThat(commitIdDatabaseFile).exists();
        try (FileChannel ch = FileChannel.open(commitIdDatabaseFile.toPath(), StandardOpenOption.WRITE)) {
            ch.truncate(0);
        }

        // Open the repository again to see if the commit ID database is regenerated automatically.
        repo = GitRepositoryV2.open(mock(Project.class), repoDir, commonPool(), null);
        try {
            assertThat(repo.normalizeNow(Revision.HEAD)).isEqualTo(headRevision);
            for (int i = startRevision; i < startRevision + numCommits; i++) {
                assertThat(repo.find(new Revision(i + 1), "/" + i + ".txt").join()).hasSize(1);
            }
        } finally {
            repo.internalClose();
        }

        assertThat(commitIdDatabaseFile).exists();
        assertThat(repo.creationTimeMillis()).isEqualTo(1000);
        assertThat(repo.author()).isEqualTo(Author.SYSTEM);
        assertThat(Files.size(commitIdDatabaseFile.toPath())).isEqualTo((numCommits + 1) * 24L);
    }

    private static GitRepositoryV2 createRepoWithFirstRevision(File repoDir, int startRevision) {
        final GitRepositoryV2 repo = new GitRepositoryV2(mock(Project.class), repoDir,
                                                         commonPool(),
                                                         1000, Author.SYSTEM, null);
        if (startRevision == 1) {
            addCommit(repo, startRevision);
            addCommit(repo, startRevision + 1);
            return repo;
        }

        for (int i = 1; i < startRevision; i++) {
            addCommit(repo, i);
        }
        assertThat(repo.shouldCreateRollingRepository(1, 0).major()).isEqualTo(startRevision);
        repo.createRollingRepository(new Revision(startRevision), 1, 0);
        // Now the first revision of secondary repository is startRevision;
        final InternalRepository secondaryRepo = repo.secondaryRepo;
        assertThat(secondaryRepo.commitIdDatabase().firstRevision().major()).isEqualTo(startRevision);

        addCommit(repo, startRevision);
        addCommit(repo, startRevision + 1);
        assertThat(repo.shouldCreateRollingRepository(1, 0).major()).isEqualTo(startRevision + 2);
        repo.createRollingRepository(new Revision(startRevision + 2), 1, 0);

        // The secondary repo is promoted.
        assertThat(repo.primaryRepo).isSameAs(secondaryRepo);
        assertThat(repo.primaryRepo.commitIdDatabase().firstRevision().major()).isEqualTo(startRevision);
        assertThat(repo.primaryRepo.commitIdDatabase().headRevision().major()).isEqualTo(startRevision + 2);
        return repo;
    }

    private static Revision addCommit(GitRepositoryV2 repo, int i) {
        return repo.commit(Revision.HEAD, 0, Author.SYSTEM, "", Change.ofTextUpsert("/" + i + ".txt", ""))
                   .join()
                   .revision();
    }

    private static ObjectId randomCommitId() {
        final byte[] commitId = new byte[20];
        ThreadLocalRandom.current().nextBytes(commitId);
        return ObjectId.fromRaw(commitId);
    }
}
