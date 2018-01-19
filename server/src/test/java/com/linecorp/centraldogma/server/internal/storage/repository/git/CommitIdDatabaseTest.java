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
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.StorageException;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.RevisionNotFoundException;

public class CommitIdDatabaseTest {

    @Rule
    public final TemporaryFolder tmpDir = new TemporaryFolder();

    private CommitIdDatabase db;

    @Before
    public void init() {
        db = new CommitIdDatabase(tmpDir.getRoot());
    }

    @After
    public void destroy() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    public void emptyDatabase() throws Exception {
        assertThat(db.headRevision()).isNull();
        assertThatThrownBy(() -> db.get(Revision.INIT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void simpleAccess() throws Exception {
        final int numCommits = 10;
        final ObjectId[] expectedCommitIds = new ObjectId[numCommits + 1];
        for (int i = 1; i <= numCommits; i++) {
            final Revision revision = new Revision(i);
            final ObjectId commitId = randomCommitId();
            expectedCommitIds[i] = commitId;
            db.put(revision, commitId);
            assertThat(db.headRevision()).isEqualTo(revision);
        }

        for (int i = 1; i <= numCommits; i++) {
            assertThat(db.get(new Revision(i))).isEqualTo(expectedCommitIds[i]);
        }

        assertThatThrownBy(() -> db.get(Revision.HEAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute revision");
        assertThatThrownBy(() -> db.get(new Revision(numCommits + 1)))
                .isInstanceOf(RevisionNotFoundException.class);
    }

    @Test
    public void truncatedDatabase() throws Exception {
        db.put(Revision.INIT, randomCommitId());
        db.close();

        // Truncate the database file.
        try (FileChannel f = FileChannel.open(new File(tmpDir.getRoot(), "commit_ids.dat").toPath(),
                         StandardOpenOption.APPEND)) {

            assertThat(f.size()).isEqualTo(24);
            f.truncate(23);
        }

        assertThatThrownBy(() -> new CommitIdDatabase(tmpDir.getRoot()))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("incorrect file length");
    }

    @Test
    public void mismatchingRevision() throws Exception {
        db.close();

        // Append a record with incorrect revision number.
        try (FileChannel f = FileChannel.open(new File(tmpDir.getRoot(), "commit_ids.dat").toPath(),
                                              StandardOpenOption.APPEND)) {

            final ByteBuffer buf = ByteBuffer.allocate(24);
            buf.putInt(42); // Expected to be 1.
            randomCommitId().copyRawTo(buf);
            buf.flip();
            do {
                f.write(buf);
            } while (buf.hasRemaining());

            assertThat(f.size()).isEqualTo(buf.capacity());
        }

        // Reopen the database and see if it fails to resolve the revision 1.
        db = new CommitIdDatabase(tmpDir.getRoot());
        assertThatThrownBy(() -> db.get(Revision.INIT))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("incorrect revision number");
    }

    @Test
    public void rebuildingBadDatabase() throws Exception {
        final int numCommits = 10;
        final File repoDir = tmpDir.getRoot();
        final File commitIdDatabaseFile = new File(repoDir, "commit_ids.dat");

        // Create a repository which contains some commits.
        GitRepository repo = new GitRepository(mock(Project.class), repoDir, commonPool(), 0, Author.SYSTEM);
        Revision headRevision = null;
        try {
            for (int i = 1; i <= numCommits; i++) {
                headRevision = repo.commit(new Revision(i), 0, Author.SYSTEM, "",
                                           Change.ofTextUpsert("/" + i + ".txt", "")).join();
            }
        } finally {
            repo.close();
        }

        // Wipe out the commit ID database.
        assertThat(commitIdDatabaseFile).exists();
        try (FileChannel ch = FileChannel.open(commitIdDatabaseFile.toPath(), StandardOpenOption.WRITE)) {
            ch.truncate(0);
        }

        // Open the repository again to see if the commit ID database is regenerated automatically.
        repo = new GitRepository(mock(Project.class), repoDir, commonPool());
        try {
            assertThat(repo.normalizeNow(Revision.HEAD)).isEqualTo(headRevision);
            for (int i = 1; i <= numCommits; i++) {
                assertThat(repo.find(new Revision(i + 1), "/" + i + ".txt").join()).hasSize(1);
            }
        } finally {
            repo.close();
        }

        assertThat(Files.size(commitIdDatabaseFile.toPath())).isEqualTo((numCommits + 1) * 24L);
    }

    private static ObjectId randomCommitId() {
        final byte[] commitId = new byte[20];
        ThreadLocalRandom.current().nextBytes(commitId);
        return ObjectId.fromRaw(commitId);
    }
}
