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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.encryption.NoopEncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.Project;

class RecoverRepositoryTest {

    private static final String REPO = "test_repo";

    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
    }

    @Test
    void skipsWhenAlreadyConverged() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo);

        final Revision head = repo.normalizeNow(Revision.HEAD);
        final String headId = commitId(repo, head);
        final List<ReplayCommit> payload = mgr.buildRecoveryPayload(REPO, new Revision(3));

        // The source (and any healthy replica) is already at the target -> recovery is a no-op and the
        // GitRepository instance is left untouched (not swapped).
        mgr.recoverRepository(REPO, new Revision(2), payload);
        assertThat(mgr.get(REPO)).isSameAs(repo);
        assertThat(repo.normalizeNow(Revision.HEAD)).isEqualTo(head);
        assertThat(commitId(repo, head)).isEqualTo(headId);
    }

    @Test
    void resetsAndReplaysToConverge() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository source = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(source);

        // Capture the source-of-truth head and per-revision commit ids, then build the replay payload
        // through the production path.
        final Revision sourceHead = source.normalizeNow(Revision.HEAD); // r5
        final String sourceHeadId = commitId(source, sourceHead);
        final String sourceId3 = commitId(source, new Revision(3));
        final String sourceId4 = commitId(source, new Revision(4));
        final List<ReplayCommit> payload = mgr.buildRecoveryPayload(REPO, new Revision(3));

        // Diverge: push a 6th revision with different content so the repository is ahead of the payload.
        source.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                      ImmutableList.of(Change.ofTextUpsert("/f.txt", "diverged")), false).join();
        assertThat(source.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(6));

        // Recover: reset to r2 and replay r3..r5 (a multi-file commit, a JSON commit and a removal)
        // -> converge back to the exact source commit ids, dropping r6.
        mgr.recoverRepository(REPO, new Revision(2), payload);

        final GitRepository recovered = (GitRepository) mgr.get(REPO);
        assertThat(recovered).isNotSameAs(source); // the instance was swapped
        assertThat(recovered.normalizeNow(Revision.HEAD)).isEqualTo(sourceHead); // r5, not r6
        assertThat(commitId(recovered, new Revision(3))).isEqualTo(sourceId3);
        assertThat(commitId(recovered, new Revision(4))).isEqualTo(sourceId4);
        assertThat(commitId(recovered, sourceHead)).isEqualTo(sourceHeadId);
        // The replayed content matches the source history: /f.txt was removed at r5 and /g.txt remains.
        assertThat(recovered.getOrNull(sourceHead, "/f.txt").join()).isNull();
        assertThat(recovered.getOrNull(sourceHead, "/g.txt").join().contentAsText()).isEqualTo("g\n");
        // The diverged r6 no longer exists.
        assertThatThrownBy(() -> recovered.commitIdDatabase().get(new Revision(6)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rollsBackWhenACommitIdDoesNotMatch() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo);
        final List<ReplayCommit> payload = new ArrayList<>(mgr.buildRecoveryPayload(REPO, new Revision(3)));

        // Diverge so recovery does not short-circuit as already-converged.
        repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();
        final Revision headBefore = repo.normalizeNow(Revision.HEAD); // r6
        final String headIdBefore = commitId(repo, headBefore);

        // Corrupt the expected commit id of the last replayed commit so the apply detects divergence.
        final ReplayCommit last = payload.get(payload.size() - 1);
        payload.set(payload.size() - 1, new ReplayCommit(
                last.revision(), last.timestampMillis(), last.author(), last.summary(), last.detail(),
                last.markup(), last.changes(), "0000000000000000000000000000000000000000"));

        assertThatThrownBy(() -> mgr.recoverRepository(REPO, new Revision(2), payload))
                .isInstanceOf(StorageException.class);

        // The repository must be rolled back to its pre-recovery HEAD and stay usable.
        final GitRepository afterFailure = (GitRepository) mgr.get(REPO);
        assertThat(afterFailure.normalizeNow(Revision.HEAD)).isEqualTo(headBefore);
        assertThat(commitId(afterFailure, headBefore)).isEqualTo(headIdBefore);
        // A subsequent read still works (the commit-id database is consistent).
        assertThat(afterFailure.getOrNull(headBefore, "/g.txt").join().contentAsText())
                .isEqualTo("diverged\n");
    }

    @Test
    void rejectsAnOutOfRangeFromRevision() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5

        assertThatThrownBy(() -> mgr.buildRecoveryPayload(REPO, new Revision(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[2, 5]");
        assertThatThrownBy(() -> mgr.buildRecoveryPayload(REPO, new Revision(6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[2, 5]");
        assertThatThrownBy(() -> mgr.buildRecoveryPayload(REPO, new Revision(-1)))
                .isInstanceOf(IllegalArgumentException.class);

        // A repository with only its creation commit has nothing to replay.
        mgr.create("empty_repo", Author.SYSTEM);
        assertThatThrownBy(() -> mgr.buildRecoveryPayload("empty_repo", new Revision(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no replayable revision");
    }

    /**
     * The payload crosses the replication log as one entry and is materialized in memory by every
     * replica, so buildRecoveryPayload() itself must refuse to build an unbounded one.
     */
    @Test
    void rejectsAnOversizedRecoveryPayload() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5, so r3..r5 is 3 commits

        final int commitLimit = GitRepositoryManager.maxRecoveryCommits;
        final long byteLimit = GitRepositoryManager.maxRecoveryPayloadBytes;
        try {
            GitRepositoryManager.maxRecoveryCommits = 2;
            assertThatThrownBy(() -> mgr.buildRecoveryPayload(REPO, new Revision(3)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too many revisions");
            // Two commits fit.
            assertThat(mgr.buildRecoveryPayload(REPO, new Revision(4))).hasSize(2);

            GitRepositoryManager.maxRecoveryCommits = commitLimit;
            GitRepositoryManager.maxRecoveryPayloadBytes = 1;
            assertThatThrownBy(() -> mgr.buildRecoveryPayload(REPO, new Revision(3)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("larger than");
        } finally {
            GitRepositoryManager.maxRecoveryCommits = commitLimit;
            GitRepositoryManager.maxRecoveryPayloadBytes = byteLimit;
        }
    }

    @Test
    void rejectsAReplicaMissingTheResetBase() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository source = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(source); // head == r5
        final List<ReplayCommit> payload = mgr.buildRecoveryPayload(REPO, new Revision(5));

        // A replica whose head is below the reset revision (r4) lacks the shared base history.
        final GitRepositoryManager lagging = newRepositoryManager(Files.createTempDir());
        final GitRepository laggingRepo = (GitRepository) lagging.create(REPO, Author.SYSTEM);
        laggingRepo.commit(new Revision(1), 2000L, Author.SYSTEM, "add f", "d", Markup.PLAINTEXT,
                           ImmutableList.of(Change.ofTextUpsert("/f.txt", "v2")), false).join();

        assertThatThrownBy(() -> lagging.recoverRepository(REPO, new Revision(4), payload))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("missing the shared base history");
    }

    /**
     * Pushes r2..r5 covering the change shapes recovery must replay byte-identically: a text upsert (r2),
     * a multi-file commit (r3), a JSON upsert (r4) and a removal (r5).
     */
    private static void pushMixedRevisions(GitRepository repo) {
        repo.commit(new Revision(1), 2000L, Author.SYSTEM, "add f", "detail2", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/f.txt", "v2")), false).join();
        repo.commit(new Revision(2), 3000L, Author.SYSTEM, "add g and h", "detail3", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "g"),
                                     Change.ofTextUpsert("/h.txt", "h")), false).join();
        repo.commit(new Revision(3), 4000L, Author.SYSTEM, "add json", "detail4", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }")), false).join();
        repo.commit(new Revision(4), 5000L, Author.SYSTEM, "remove f", "detail5", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofRemoval("/f.txt")), false).join();
    }

    private static String commitId(GitRepository repo, Revision revision) {
        return repo.commitIdDatabase().get(revision).name();
    }

    private GitRepositoryManager newRepositoryManager() {
        return newRepositoryManager(tempDir.toFile());
    }

    private static GitRepositoryManager newRepositoryManager(java.io.File rootDir) {
        final Project mock = mock(Project.class);
        lenient().when(mock.name()).thenReturn("test_project");
        return new GitRepositoryManager(mock, rootDir, ForkJoinPool.commonPool(),
                                        MoreExecutors.directExecutor(), null,
                                        NoopEncryptionStorageManager.INSTANCE);
    }
}
