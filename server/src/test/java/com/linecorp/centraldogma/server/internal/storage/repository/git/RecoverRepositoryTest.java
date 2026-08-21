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
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.RepositoryRecoveryException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.encryption.NoopEncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.RepositoryListener;

// A recovery that deadlocks would otherwise hang the build until the CI job is killed, which says nothing
// about which test broke.
@Timeout(60)
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
        final List<ReplayCommit> payload = mgr.buildRecoveryPayload(REPO, new Revision(3),
                                                                                  new Revision(5));

        // The source (and any healthy replica) is already at the target -> recovery is a no-op and the
        // repository is left untouched.
        mgr.recoverRepository(REPO, new Revision(2), payload);
        assertThat(mgr.get(REPO)).isSameAs(repo);
        assertThat(repo.cacheGeneration()).isZero();
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
        final List<ReplayCommit> payload = mgr.buildRecoveryPayload(REPO, new Revision(3),
                                                                                  new Revision(5));

        // Diverge: push a 6th revision with different content so the repository is ahead of the payload.
        source.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                      ImmutableList.of(Change.ofTextUpsert("/f.txt", "diverged")), false).join();
        assertThat(source.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(6));

        // Recover: reset to r2 and replay r3..r5 (a multi-file commit, a JSON commit and a removal)
        // -> converge back to the exact source commit ids, dropping r6.
        mgr.recoverRepository(REPO, new Revision(2), payload);

        final GitRepository recovered = (GitRepository) mgr.get(REPO);
        // Rewritten in place; the bumped count is what makes every cache entry from the old history
        // unreachable.
        assertThat(recovered).isSameAs(source);
        assertThat(recovered.cacheGeneration()).isPositive();
        assertThat(recovered.normalizeNow(Revision.HEAD)).isEqualTo(sourceHead); // r5, not r6
        assertThat(commitId(recovered, new Revision(3))).isEqualTo(sourceId3);
        assertThat(commitId(recovered, new Revision(4))).isEqualTo(sourceId4);
        assertThat(commitId(recovered, sourceHead)).isEqualTo(sourceHeadId);
        // The replayed content matches the source history: /f.txt was removed at r5 and /g.txt remains.
        assertThat(recovered.getOrNull(sourceHead, "/f.txt").join()).isNull();
        assertThat(recovered.getOrNull(sourceHead, "/g.txt").join().contentAsText()).isEqualTo("g\n");
        // The diverged r6 no longer exists.
        assertThatThrownBy(() -> recovered.commitIdDatabase().get(new Revision(6)))
                .isInstanceOf(RevisionNotFoundException.class);
    }

    @Test
    void rollsBackToTheToRevision() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5
        final String id4 = commitId(repo, new Revision(4));

        // A range that stops short of the head: r5 is discarded even on the replica the payload was
        // built from, and r4 becomes the new head.
        final List<ReplayCommit> payload = mgr.buildRecoveryPayload(REPO, new Revision(3),
                                                                                  new Revision(4));
        assertThat(mgr.recoverRepository(REPO, new Revision(2), payload)).isTrue();

        final GitRepository recovered = (GitRepository) mgr.get(REPO);
        assertThat(recovered.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(4));
        assertThat(commitId(recovered, new Revision(4))).isEqualTo(id4);
        assertThatThrownBy(() -> recovered.commitIdDatabase().get(new Revision(5)))
                .isInstanceOf(RevisionNotFoundException.class);
    }

    /**
     * A recovery is applied on a repository-worker thread, so it must never wait for another
     * repository-worker task: the pool is fixed-size, and a read of the repository being recovered parks a
     * worker on the write lock the recovery holds. Driving it through a one-thread pool deadlocks outright
     * if any step hops back onto the pool. The other tests here cannot catch this - they pass a
     * ForkJoinPool, whose join() spawns a compensation thread and papers over the self-dependency.
     */
    @Test
    void recoveryNeverWaitsOnTheRepositoryWorkerPool() throws Exception {
        final ExecutorService repositoryWorker = Executors.newFixedThreadPool(1);
        try {
            final Project project = mock(Project.class);
            lenient().when(project.name()).thenReturn("test_project");
            final GitRepositoryManager mgr = new GitRepositoryManager(
                    project, tempDir.toFile(), repositoryWorker, MoreExecutors.directExecutor(), null,
                    NoopEncryptionStorageManager.INSTANCE);
            final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
            pushMixedRevisions(repo);
            final List<ReplayCommit> payload = mgr.buildRecoveryPayload(REPO, new Revision(3),
                                                                                      new Revision(5));

            // Diverge, so the recovery resets and replays instead of short-circuiting as converged.
            repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                        ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();

            // Apply it the way StandaloneCommandExecutor does: from the repository worker.
            final Future<Boolean> recovered = repositoryWorker.submit(
                    () -> mgr.recoverRepository(REPO, new Revision(2), payload));
            assertThat(recovered.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(mgr.get(REPO).normalizeNow(Revision.HEAD)).isEqualTo(new Revision(5));
        } finally {
            repositoryWorker.shutdownNow();
        }
    }

    /**
     * A watch waits for a revision of a history the recovery discards, so it must be failed rather than
     * left waiting for its own timeout - whether or not the replay finished.
     */
    @Test
    void failsWatchersWhetherTheRecoverySucceedsOrNot() throws Exception {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5
        final List<ReplayCommit> payload = new ArrayList<>(
                mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)));
        repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();

        final CompletableFuture<Revision> watch = repo.watch(Revision.HEAD, "/**", false);
        assertThat(mgr.recoverRepository(REPO, new Revision(2), payload)).isTrue();
        assertThatThrownBy(() -> watch.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RepositoryRecoveryException.class);

        // Same promise on the failure path: the history was rewritten even though the replay gave up.
        final ReplayCommit last = payload.get(payload.size() - 1);
        payload.set(payload.size() - 1, new ReplayCommit(
                last.revision(), last.timestampMillis(), last.author(), last.summary(), last.detail(),
                last.markup(), last.changes(), "0000000000000000000000000000000000000000"));
        repo.commit(new Revision(5), 7000L, Author.SYSTEM, "diverged again", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged again")), false).join();

        final CompletableFuture<Revision> watchAcrossFailure = repo.watch(Revision.HEAD, "/**", false);
        assertThatThrownBy(() -> mgr.recoverRepository(REPO, new Revision(2), payload))
                .isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> watchAcrossFailure.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RepositoryRecoveryException.class);
    }

    @Test
    void keepsListenersWatchingAfterARecovery() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5
        final List<ReplayCommit> payload = mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5));

        // A server-side listener has nobody to retry on its behalf, so a recovery must leave it watching.
        final BlockingQueue<Map<String, Entry<?>>> updates = new LinkedBlockingQueue<>();
        repo.addListener(RepositoryListener.of("/**", updates::add));
        await().untilAsserted(() -> assertThat(updates).isNotEmpty());
        updates.clear();

        // Diverge, then recover, which discards the divergence and closes the watchers.
        repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();
        assertThat(mgr.recoverRepository(REPO, new Revision(2), payload)).isTrue();

        // A commit after the recovery still reaches the listener.
        updates.clear();
        repo.commit(Revision.HEAD, 7000L, Author.SYSTEM, "after recovery", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/after-recovery.txt", "1")), false).join();
        await().untilAsserted(() -> assertThat(updates).isNotEmpty());
    }

    @Test
    void leavesThePartialHistoryReadableWhenATreeIdDoesNotMatch() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo);
        final List<ReplayCommit> payload = new ArrayList<>(
                mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)));

        // Diverge so recovery does not short-circuit as already-converged.
        repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();

        // Corrupt the expected tree id of the last replayed commit so the apply detects divergence.
        final ReplayCommit last = payload.get(payload.size() - 1);
        payload.set(payload.size() - 1, new ReplayCommit(
                last.revision(), last.timestampMillis(), last.author(), last.summary(), last.detail(),
                last.markup(), last.changes(), "0000000000000000000000000000000000000000"));

        assertThatThrownBy(() -> mgr.recoverRepository(REPO, new Revision(2), payload))
                .isInstanceOf(StorageException.class);

        // The recovery gives up where it stood, and the repository keeps answering reads from the history
        // it holds; an administrator recovers it again.
        final GitRepository afterFailure = (GitRepository) mgr.get(REPO);
        final Revision head = afterFailure.normalizeNow(Revision.HEAD);
        assertThat(head).isLessThan(new Revision(6));
        assertThat(afterFailure.find(head, "/**", ImmutableMap.of()).join()).isNotEmpty();
    }

    @Test
    void rejectsAnOutOfRangeFromRevision() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5

        assertThatThrownBy(() -> mgr.buildRecoveryPayload(REPO, new Revision(1), new Revision(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[2, 5]");
        assertThatThrownBy(() -> mgr.buildRecoveryPayload(REPO, new Revision(6), new Revision(6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[2, 5]");
        assertThatThrownBy(() -> mgr.buildRecoveryPayload(REPO, new Revision(-1),
                                                                        new Revision(5)))
                .isInstanceOf(IllegalArgumentException.class);

        // A repository with only its creation commit has nothing to replay.
        mgr.create("empty_repo", Author.SYSTEM);
        assertThatThrownBy(() -> mgr.buildRecoveryPayload("empty_repo", new Revision(2),
                                                                        new Revision(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no replayable revision");
    }

    /**
     * The payload crosses the replication log as one entry and is materialized in memory by every
     * replica, so buildRecoveryPayload() itself must refuse to build an unbounded one.
     */
    @Test
    void rejectsTooManyRevisions() {
        RepositoryRecovery.checkCommitCount("foo/bar", RepositoryRecovery.MAX_RECOVERY_COMMITS);
        assertThatThrownBy(() -> RepositoryRecovery.checkCommitCount(
                "foo/bar", RepositoryRecovery.MAX_RECOVERY_COMMITS + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many revisions");
    }

    @Test
    void rejectsAReplicaMissingTheResetBase() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository source = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(source); // head == r5
        final List<ReplayCommit> payload = mgr.buildRecoveryPayload(REPO, new Revision(5),
                                                                                  new Revision(5));

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
     * A tree names the content of one revision and nothing before it, so a replica whose head happens to
     * match must still be repaired when an earlier revision does not.
     */
    @Test
    void recoversWhenOnlyTheHeadTreeMatches() {
        final GitRepositoryManager sourceMgr = newRepositoryManager(new File(tempDir.toFile(), "source"));
        final GitRepository source = (GitRepository) sourceMgr.create(REPO, Author.SYSTEM);
        source.commit(new Revision(1), 2000L, Author.SYSTEM, "a=1", "", Markup.PLAINTEXT,
                      ImmutableList.of(Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }")), false).join();
        source.commit(new Revision(2), 3000L, Author.SYSTEM, "a=2", "", Markup.PLAINTEXT,
                      ImmutableList.of(Change.ofJsonUpsert("/a.json", "{ \"a\": 2 }")), false).join();
        final List<ReplayCommit> payload = sourceMgr.buildRecoveryPayload(REPO, new Revision(2),
                                                                         new Revision(3));

        // The replica holds the wrong content at r2 and the source's content at r3, so its head tree
        // matches the source's while its history does not.
        final GitRepositoryManager replicaMgr = newRepositoryManager(new File(tempDir.toFile(), "replica"));
        final GitRepository replica = (GitRepository) replicaMgr.create(REPO, Author.SYSTEM);
        replica.commit(new Revision(1), 2000L, Author.SYSTEM, "a=99", "", Markup.PLAINTEXT,
                       ImmutableList.of(Change.ofJsonUpsert("/a.json", "{ \"a\": 99 }")), false).join();
        replica.commit(new Revision(2), 3000L, Author.SYSTEM, "a=2", "", Markup.PLAINTEXT,
                       ImmutableList.of(Change.ofJsonUpsert("/a.json", "{ \"a\": 2 }")), false).join();
        assertThat(replica.getOrNull(new Revision(3), "/a.json").join().contentAsText())
                .isEqualTo(source.getOrNull(new Revision(3), "/a.json").join().contentAsText());

        assertThat(replicaMgr.recoverRepository(REPO, new Revision(1), payload)).isTrue();

        assertThat(replica.getOrNull(new Revision(2), "/a.json").join().contentAsText())
                .isEqualTo(source.getOrNull(new Revision(2), "/a.json").join().contentAsText());
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
