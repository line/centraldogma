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
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.encryption.NoopEncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.DiffResultType;

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
        pushRevisions(repo, 2, 5);

        final Revision head = repo.normalizeNow(Revision.HEAD);
        final String headId = commitId(repo, head);
        final List<ReplayCommit> payload = buildPayload(repo, 3, 5);

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
        pushRevisions(source, 2, 5);

        // Capture the source-of-truth head and per-revision commit ids, then build the replay payload.
        final Revision sourceHead = source.normalizeNow(Revision.HEAD); // r5
        final String sourceHeadId = commitId(source, sourceHead);
        final String sourceId4 = commitId(source, new Revision(4));
        final List<ReplayCommit> payload = buildPayload(source, 3, 5);

        // Diverge: push a 6th revision with different content so the repository is ahead of the payload.
        source.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                      ImmutableList.of(Change.ofTextUpsert("/f.txt", "diverged")), false).join();
        assertThat(source.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(6));

        // Recover: reset to r2 and replay r3..r5 -> converge back to the exact source commit ids, dropping r6.
        mgr.recoverRepository(REPO, new Revision(2), payload);

        final GitRepository recovered = (GitRepository) mgr.get(REPO);
        assertThat(recovered).isNotSameAs(source); // the instance was swapped
        assertThat(recovered.normalizeNow(Revision.HEAD)).isEqualTo(sourceHead); // r5, not r6
        assertThat(commitId(recovered, sourceHead)).isEqualTo(sourceHeadId);
        assertThat(commitId(recovered, new Revision(4))).isEqualTo(sourceId4);
        // The diverged r6 no longer exists.
        assertThatThrownBy(() -> recovered.commitIdDatabase().get(new Revision(6)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rollsBackWhenACommitIdDoesNotMatch() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushRevisions(repo, 2, 5);

        // Diverge so recovery does not short-circuit as already-converged.
        repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/f.txt", "diverged")), false).join();
        final Revision headBefore = repo.normalizeNow(Revision.HEAD); // r6
        final String headIdBefore = commitId(repo, headBefore);

        // Corrupt the expected commit id of the last replayed commit so the apply detects divergence.
        final List<ReplayCommit> payload = buildPayload(repo, 3, 5);
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
        assertThat(afterFailure.getOrNull(headBefore, "/f.txt").join().contentAsText()).isEqualTo("diverged\n");
    }

    private void pushRevisions(GitRepository repo, int from, int to) {
        for (int i = from; i <= to; i++) {
            repo.commit(new Revision(i - 1), i * 1000L, Author.SYSTEM, "summary" + i, "detail" + i,
                        Markup.PLAINTEXT, ImmutableList.of(Change.ofTextUpsert("/f.txt", "v" + i)), false)
                .join();
        }
    }

    /**
     * Builds the replay payload for {@code from..to} the same way {@code RecoveryPayloadBuilder} does.
     */
    private static List<ReplayCommit> buildPayload(GitRepository repo, int from, int to) {
        final List<ReplayCommit> payload = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            final Revision revision = new Revision(i);
            final List<Commit> commits = repo.history(revision, revision, "/**").join();
            final Commit commit = commits.get(0);
            final Map<String, Change<?>> changes =
                    repo.diff(new Revision(i - 1), revision, "/**", DiffResultType.PATCH_TO_TEXT_UPSERT).join();
            payload.add(new ReplayCommit(revision, commit.when(), commit.author(), commit.summary(),
                                         commit.detail(), commit.markup(), changes.values(),
                                         commitId(repo, revision)));
        }
        return payload;
    }

    private static String commitId(GitRepository repo, Revision revision) {
        return repo.commitIdDatabase().get(revision).name();
    }

    private GitRepositoryManager newRepositoryManager() {
        final Project mock = mock(Project.class);
        lenient().when(mock.name()).thenReturn("test_project");
        return new GitRepositoryManager(mock, tempDir.toFile(), ForkJoinPool.commonPool(),
                                        MoreExecutors.directExecutor(), null,
                                        NoopEncryptionStorageManager.INSTANCE);
    }
}
