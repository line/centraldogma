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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
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
import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.command.RecoverRepositoryCommand;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.encryption.NoopEncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;
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
        assertThat(mgr.recoverRepository(REPO, new Revision(2), payload)).isFalse();
        assertThat(mgr.get(REPO)).isSameAs(repo);
        assertThat(repo.cacheGeneration()).isZero();
        assertThat(repo.lastRecoveryRevision()).isEqualTo(new Revision(5));
        assertThat(repo.jGitRepository().getConfig()
                       .getInt("centraldogma", null, "lastRecoveryRevision", 0))
                .isEqualTo(5);
        assertThat(repo.normalizeNow(Revision.HEAD)).isEqualTo(head);
        assertThat(commitId(repo, head)).isEqualTo(headId);
    }

    @Test
    void padsRecoveryToTheNextRevisionAndContinuesAtTheFollowingRevision() throws Exception {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5
        final String sourceTreeId = repo.head().treeId();
        final List<ReplayCommit> payload = withPaddingThrough(
                mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(7));

        // The local r6 is discarded. The recovery must leave the repository at r7, so a client that last
        // observed r6 never receives a lower revision after the rewrite.
        repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();
        assertThat(repo.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(6));

        assertThat(mgr.recoverRepository(REPO, new Revision(2), payload)).isTrue();

        assertThat(repo.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(7));
        assertThat(repo.getOrNull(new Revision(7), "/f.txt").join()).isNull();
        assertThat(repo.getOrNull(new Revision(7), "/g.txt").join().contentAsText()).isEqualTo("g\n");
        assertThat(repo.head().treeId()).isEqualTo(sourceTreeId);
        assertThat(repo.lastRecoveryRevision()).isEqualTo(new Revision(7));

        // r6 and r7 have the same recovered tree, but a client that saw the discarded r6 must refresh.
        assertThat(repo.watch(new Revision(6), "/**", false).get(30, TimeUnit.SECONDS))
                .isEqualTo(new Revision(7));
        final Entry<String> recoveredEntry =
                repo.watch(new Revision(6), Query.ofText("/g.txt")).get(30, TimeUnit.SECONDS);
        assertThat(recoveredEntry.revision()).isEqualTo(new Revision(7));
        assertThat(recoveredEntry.content()).isEqualTo("g\n");
        assertThatThrownBy(() -> repo.watch(new Revision(6), "/not-present.txt", true).join())
                .hasCauseInstanceOf(EntryNotFoundException.class);

        final CommitResult afterRecovery = repo.commit(
                Revision.HEAD, 8000L, Author.SYSTEM, "after recovery", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofTextUpsert("/after-recovery.txt", "1")), false).join();
        assertThat(afterRecovery.revision()).isEqualTo(new Revision(8));

        mgr.close(ShuttingDownException::new);
        final GitRepositoryManager reopened = newRepositoryManager(tempDir.toFile());
        try {
            final GitRepository reopenedRepo = (GitRepository) reopened.get(REPO);
            assertThat(reopenedRepo.lastRecoveryRevision()).isEqualTo(new Revision(7));
            assertThat(reopenedRepo.watch(new Revision(6), "/**", false).get(30, TimeUnit.SECONDS))
                    .isEqualTo(new Revision(8));
            final Entry<String> reopenedEntry =
                    reopenedRepo.watch(new Revision(6), Query.ofText("/g.txt"))
                                .get(30, TimeUnit.SECONDS);
            assertThat(reopenedEntry.revision()).isEqualTo(new Revision(8));
            assertThat(reopenedEntry.content()).isEqualTo("g\n");
        } finally {
            reopened.close(ShuttingDownException::new);
        }
    }

    @Test
    void rejectsRecoveryWhenLocalHeadIsNotBelowRecoveryRevision() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5
        final List<ReplayCommit> payload = withPaddingThrough(
                mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(7));
        repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();
        repo.commit(new Revision(6), 7000L, Author.SYSTEM, "still diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/h.txt", "diverged")), false).join();

        final String headCommitId = commitId(repo, new Revision(7));
        final int cacheGeneration = repo.cacheGeneration();
        assertThatThrownBy(
                () -> mgr.recoverRepository(REPO, new Revision(2), payload))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("must be greater than the current head");

        assertThat(repo.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(7));
        assertThat(commitId(repo, new Revision(7))).isEqualTo(headCommitId);
        assertThat(repo.cacheGeneration()).isEqualTo(cacheGeneration);
        assertThat(repo.lastRecoveryRevision()).isEqualTo(Revision.INIT);
    }

    @Test
    void recognizesThePaddedHistoryOnRetry() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5
        final List<ReplayCommit> payload = withPaddingThrough(
                mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(7));
        repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();

        assertThat(mgr.recoverRepository(REPO, new Revision(2), payload)).isTrue();
        final String paddedRevision6Id = commitId(repo, new Revision(6));
        final String paddedRevision7Id = commitId(repo, new Revision(7));
        final int cacheGeneration = repo.cacheGeneration();

        assertThat(mgr.recoverRepository(REPO, new Revision(2), payload)).isFalse();

        assertThat(repo.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(7));
        assertThat(commitId(repo, new Revision(6))).isEqualTo(paddedRevision6Id);
        assertThat(commitId(repo, new Revision(7))).isEqualTo(paddedRevision7Id);
        assertThat(repo.cacheGeneration()).isEqualTo(cacheGeneration);
        assertThat(repo.lastRecoveryRevision()).isEqualTo(new Revision(7));
    }

    @Test
    void replaysPriorPaddingCommitsDuringALaterRecovery() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5
        final List<ReplayCommit> firstPayload = withPaddingThrough(
                mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(7));
        repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();
        assertThat(mgr.recoverRepository(REPO, new Revision(2), firstPayload)).isTrue();

        final String recoveredTreeId = repo.head().treeId();
        final List<ReplayCommit> secondPayload = withPaddingThrough(
                mgr.buildRecoveryPayload(REPO, new Revision(5), new Revision(7)), new Revision(9));
        assertThat(secondPayload.get(1).changes()).isEmpty();
        assertThat(secondPayload.get(2).changes()).isEmpty();
        repo.commit(new Revision(7), 8000L, Author.SYSTEM, "diverged again", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged again")), false).join();

        assertThat(mgr.recoverRepository(REPO, new Revision(4), secondPayload)).isTrue();
        assertThat(repo.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(9));
        assertThat(repo.head().treeId()).isEqualTo(recoveredTreeId);
    }

    /**
     * The payload is built from a request thread, not from the repository worker, so a build that queues
     * diffs back to that pool waits for a thread it may never get.
     */
    @Test
    void buildingThePayloadNeverWaitsOnTheRepositoryWorkerPool() throws Exception {
        final ExecutorService repositoryWorker = Executors.newFixedThreadPool(1);
        try {
            final Project project = mock(Project.class);
            lenient().when(project.name()).thenReturn("test_project");
            final GitRepositoryManager mgr = new GitRepositoryManager(
                    project, tempDir.toFile(), repositoryWorker, MoreExecutors.directExecutor(), null,
                    NoopEncryptionStorageManager.INSTANCE);
            final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
            pushMixedRevisions(repo);

            // Occupy the only repository-worker thread for longer than the assertion below waits.
            final CountDownLatch occupied = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            repositoryWorker.execute(() -> {
                occupied.countDown();
                Uninterruptibles.awaitUninterruptibly(release);
            });
            assertThat(occupied.await(30, TimeUnit.SECONDS)).isTrue();

            try {
                final ExecutorService requestThread = Executors.newSingleThreadExecutor();
                try {
                    final Future<List<ReplayCommit>> payload = requestThread.submit(
                            () -> mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)));
                    assertThat(payload.get(30, TimeUnit.SECONDS)).hasSize(3);
                } finally {
                    requestThread.shutdownNow();
                }
            } finally {
                release.countDown();
            }
        } finally {
            repositoryWorker.shutdownNow();
        }
    }

    // A one-thread pool exposes any nested submission to repositoryWorker.
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
            final List<ReplayCommit> payload = withPaddingThrough(
                    mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(7));

            // Diverge, so the recovery resets and replays instead of short-circuiting as converged.
            repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                        ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();

            // Occupy the only repository worker with recovery itself to expose any nested hop back to it.
            final Future<Boolean> recovered = repositoryWorker.submit(
                    () -> mgr.recoverRepository(REPO, new Revision(2), payload));
            assertThat(recovered.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(mgr.get(REPO).normalizeNow(Revision.HEAD)).isEqualTo(new Revision(7));
        } finally {
            repositoryWorker.shutdownNow();
        }
    }

    @Test
    void notifiesWatchersOnlyAfterSuccessfulRecovery() throws Exception {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5
        final List<ReplayCommit> payload = withPaddingThrough(
                mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(7));
        repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();

        final CompletableFuture<Revision> watch = repo.watch(Revision.HEAD, "/**", false);
        final CompletableFuture<Revision> unchangedPathWatch =
                repo.watch(Revision.HEAD, "/not-present.txt", false);
        assertThat(mgr.recoverRepository(REPO, new Revision(2), payload)).isTrue();
        assertThat(watch.get(30, TimeUnit.SECONDS)).isEqualTo(new Revision(7));
        assertThat(unchangedPathWatch.get(30, TimeUnit.SECONDS)).isEqualTo(new Revision(7));

        // A failed attempt does not expose its partial head. A later administrator retry will wake the watch.
        final List<ReplayCommit> retryPayload = withPaddingThrough(
                mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(10));
        final List<ReplayCommit> failingPayload = new ArrayList<>(withPaddingThrough(
                mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(9)));
        final ReplayCommit first = failingPayload.get(0);
        failingPayload.set(0, new ReplayCommit(
                first.revision(), first.timestampMillis(), first.author(), first.summary(), first.detail(),
                first.markup(), first.changes(), "0000000000000000000000000000000000000000"));
        repo.commit(Revision.HEAD, 7000L, Author.SYSTEM, "diverged again", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged again")), false).join();

        final CompletableFuture<Revision> watchAcrossFailure = repo.watch(Revision.HEAD, "/**", false);
        await().untilAsserted(() -> assertThat(repo.commitWatchers.watchesMap).isNotEmpty());
        assertThatThrownBy(() -> mgr.recoverRepository(REPO, new Revision(2), failingPayload))
                .isInstanceOf(StorageException.class);
        assertThat(watchAcrossFailure).isNotDone();

        final CompletableFuture<Revision> watchStartedAfterFailure =
                repo.watch(Revision.HEAD, "/after-failure.txt", false);
        await().untilAsserted(() -> assertThat(repo.commitWatchers.watchesMap)
                .containsKey(PathPatternFilter.of("/after-failure.txt")));
        assertThat(watchStartedAfterFailure).isNotDone();

        assertThat(mgr.recoverRepository(REPO, new Revision(2), retryPayload)).isTrue();
        assertThat(watchAcrossFailure.get(30, TimeUnit.SECONDS)).isEqualTo(new Revision(10));
        assertThat(watchStartedAfterFailure.get(30, TimeUnit.SECONDS)).isEqualTo(new Revision(10));
    }

    @Test
    void keepsListenersWatchingAfterARecovery() {
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(repo); // head == r5
        final List<ReplayCommit> payload = withPaddingThrough(
                mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(7));

        // A server-side listener has nobody to retry on its behalf, so a recovery must leave it watching.
        final BlockingQueue<Map<String, Entry<?>>> updates = new LinkedBlockingQueue<>();
        repo.addListener(RepositoryListener.of("/**", updates::add));
        await().untilAsserted(() -> assertThat(updates).isNotEmpty());
        updates.clear();

        // Diverge, then recover, which discards the divergence and notifies the watchers at the final revision.
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
        final List<ReplayCommit> payload = new ArrayList<>(withPaddingThrough(
                mgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(7)));

        // Diverge so recovery does not short-circuit as already-converged.
        repo.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                    ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();

        // Corrupt the expected tree ID of the last replayed commit so the apply detects divergence.
        final ReplayCommit last = payload.get(payload.size() - 1);
        payload.set(payload.size() - 1, new ReplayCommit(
                last.revision(), last.timestampMillis(), last.author(), last.summary(), last.detail(),
                last.markup(), last.changes(), "0000000000000000000000000000000000000000"));

        assertThatThrownBy(() -> mgr.recoverRepository(REPO, new Revision(2), payload))
                .isInstanceOf(StorageException.class);

        // The recovery gives up where it stood, and the repository keeps answering reads from the history
        // it holds; an administrator recovers it again. Nothing is rolled back, so the head is exactly the
        // revision the replay reached rather than the revision it reset to.
        final GitRepository afterFailure = (GitRepository) mgr.get(REPO);
        final Revision head = afterFailure.normalizeNow(Revision.HEAD);
        assertThat(head).isEqualTo(payload.get(payload.size() - 1).revision());
        assertThat(head).isNotEqualTo(new Revision(2));
        assertThat(afterFailure.lastRecoveryRevision()).isEqualTo(Revision.INIT);
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
        final GitRepositoryManager mgr = newRepositoryManager();
        final GitRepository repo = (GitRepository) mgr.create(REPO, Author.SYSTEM);
        final int cap = RecoverRepositoryCommand.MAX_RECOVERY_COMMITS;
        for (int i = 1; i <= cap + 1; i++) {
            repo.commit(new Revision(i), 1000L + i, Author.SYSTEM, "r" + i, "", Markup.PLAINTEXT,
                        ImmutableList.of(Change.ofTextUpsert("/f.txt", "v" + i)), false).join();
        }
        final Revision head = repo.normalizeNow(Revision.HEAD); // r102 for a cap of 100

        // The whole range is one revision above the cap; the payload is refused rather than built.
        assertThatThrownBy(() -> mgr.buildRecoveryPayload(REPO, new Revision(2), head))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many revisions");

        // Exactly at the cap is accepted.
        assertThat(mgr.buildRecoveryPayload(REPO, new Revision(3), head)).hasSize(cap);
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
        final List<ReplayCommit> payload = withPaddingThrough(
                sourceMgr.buildRecoveryPayload(REPO, new Revision(2), new Revision(3)), new Revision(4));

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
     * A replica that lags behind the replayed range must not hand an intermediate revision to a watcher:
     * the recovery holds the write lock across the whole replay and notifies the watcher only after the
     * final recovery revision is committed.
     */
    @Test
    void doesNotNotifyWatchersWhileReplaying() throws Exception {
        final GitRepositoryManager sourceMgr = newRepositoryManager(new File(tempDir.toFile(), "source2"));
        final GitRepository source = (GitRepository) sourceMgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(source); // head == r5
        final List<ReplayCommit> payload = withPaddingThrough(
                sourceMgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(7));

        // The replica holds the shared base but stops at r3, so r4 and r5 are newer than anything it has.
        final GitRepositoryManager replicaMgr = newRepositoryManager(new File(tempDir.toFile(), "replica2"));
        final GitRepository replica = (GitRepository) replicaMgr.create(REPO, Author.SYSTEM);
        replica.commit(new Revision(1), 2000L, Author.SYSTEM, "add f", "detail2", Markup.PLAINTEXT,
                       ImmutableList.of(Change.ofTextUpsert("/f.txt", "v2")), false).join();
        replica.commit(new Revision(2), 3000L, Author.SYSTEM, "add g and h", "detail3", Markup.PLAINTEXT,
                       ImmutableList.of(Change.ofTextUpsert("/g.txt", "g"),
                                        Change.ofTextUpsert("/h.txt", "h")), false).join();
        assertThat(replica.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(3));

        final CompletableFuture<Revision> watch = replica.watch(new Revision(3), "/**", false);
        assertThat(watch).isNotDone();
        await().untilAsserted(() -> assertThat(replica.commitWatchers.watchesMap).isNotEmpty());

        assertThat(replicaMgr.recoverRepository(REPO, new Revision(2), payload)).isTrue();

        // Not r4 or r5, which the replay produced: only the final recovery revision is visible.
        assertThat(watch.get(30, TimeUnit.SECONDS)).isEqualTo(new Revision(7));
    }

    @Test
    void queuedWatchReturnsTheRecoveryRevisionWhenRecoveryFinishesBeforeRegistration() throws Exception {
        final GitRepositoryManager sourceMgr = newRepositoryManager(new File(tempDir.toFile(), "source3"));
        final GitRepository source = (GitRepository) sourceMgr.create(REPO, Author.SYSTEM);
        pushMixedRevisions(source);
        final List<ReplayCommit> payload = withPaddingThrough(
                sourceMgr.buildRecoveryPayload(REPO, new Revision(3), new Revision(5)), new Revision(7));

        final ExecutorService repositoryWorker = Executors.newFixedThreadPool(1);
        final CountDownLatch workerStarted = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        try {
            final GitRepositoryManager replicaMgr = newRepositoryManager(
                    new File(tempDir.toFile(), "replica3"), repositoryWorker);
            final GitRepository replica = (GitRepository) replicaMgr.create(REPO, Author.SYSTEM);
            pushMixedRevisions(replica);
            replica.commit(new Revision(5), 6000L, Author.SYSTEM, "diverged", "", Markup.PLAINTEXT,
                           ImmutableList.of(Change.ofTextUpsert("/g.txt", "diverged")), false).join();

            repositoryWorker.execute(() -> {
                workerStarted.countDown();
                Uninterruptibles.awaitUninterruptibly(releaseWorker);
            });
            assertThat(workerStarted.await(10, TimeUnit.SECONDS)).isTrue();

            final CompletableFuture<Revision> watch = replica.watch(Revision.HEAD, "/**", false);
            assertThat(replicaMgr.recoverRepository(REPO, new Revision(2), payload)).isTrue();
            releaseWorker.countDown();

            assertThat(watch.get(10, TimeUnit.SECONDS)).isEqualTo(new Revision(7));
        } finally {
            releaseWorker.countDown();
            repositoryWorker.shutdownNow();
        }
    }

    /**
     * An encrypted repository holds its content under a per-replica key, so it cannot reproduce the
     * source's trees, and its commit-id database cannot rewind at all.
     */
    @Test
    void refusesAnEncryptedRepository() {
        final Project project = mock(Project.class);
        lenient().when(project.name()).thenReturn("test_project");
        final File projectDir = new File(tempDir.toFile(), "encrypted");
        final EncryptionStorageManager encryptionStorageManager = EncryptionStorageManager.of(
                new File(tempDir.toFile(), "rocksdb").toPath(), false, "kekId");
        final GitRepositoryManager mgr = new GitRepositoryManager(
                project, projectDir, ForkJoinPool.commonPool(), MoreExecutors.directExecutor(), null,
                encryptionStorageManager);
        try {
            final String wdek = encryptionStorageManager.generateWdek().join();
            encryptionStorageManager.storeWdek(
                    new WrappedDekDetails(wdek, 1, encryptionStorageManager.kekId(), "test_project", REPO));
            mgr.create(REPO, 0, Author.SYSTEM, true);

            assertThatThrownBy(() -> mgr.buildRecoveryPayload(REPO, new Revision(2), new Revision(2)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("encrypted");
            final ReplayCommit commit =
                    new ReplayCommit(new Revision(2), 1000L, Author.SYSTEM, "s", "d", Markup.PLAINTEXT,
                                     ImmutableList.of(Change.ofTextUpsert("/a.txt", "a")),
                                     "0000000000000000000000000000000000000000");
            assertThatThrownBy(() -> mgr.recoverRepository(REPO, new Revision(1), ImmutableList.of(commit)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("encrypted");
        } finally {
            mgr.close(ShuttingDownException::new);
            encryptionStorageManager.close();
        }
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

    private static List<ReplayCommit> withPaddingThrough(List<ReplayCommit> commits,
                                                         Revision recoveryRevision) {
        final ReplayCommit last = commits.get(commits.size() - 1);
        if (last.revision().equals(recoveryRevision)) {
            return ImmutableList.copyOf(commits);
        }
        if (last.revision().compareTo(recoveryRevision) > 0) {
            throw new IllegalArgumentException("recoveryRevision must not precede the payload");
        }

        final ImmutableList.Builder<ReplayCommit> padded = ImmutableList.builder();
        padded.addAll(commits);
        for (Revision revision = last.revision().forward(1);; revision = revision.forward(1)) {
            padded.add(new ReplayCommit(revision, 0, Author.SYSTEM, "Recovery padding", "",
                                        Markup.PLAINTEXT, ImmutableList.of(), last.expectedTreeId()));
            if (revision.equals(recoveryRevision)) {
                return padded.build();
            }
        }
    }

    private static String commitId(GitRepository repo, Revision revision) {
        return repo.commitIdDatabase().get(revision).name();
    }

    private GitRepositoryManager newRepositoryManager() {
        return newRepositoryManager(tempDir.toFile());
    }

    private static GitRepositoryManager newRepositoryManager(java.io.File rootDir) {
        return newRepositoryManager(rootDir, ForkJoinPool.commonPool());
    }

    private static GitRepositoryManager newRepositoryManager(java.io.File rootDir, Executor repositoryWorker) {
        final Project mock = mock(Project.class);
        lenient().when(mock.name()).thenReturn("test_project");
        return new GitRepositoryManager(mock, rootDir, repositoryWorker,
                                        MoreExecutors.directExecutor(), null,
                                        NoopEncryptionStorageManager.INSTANCE);
    }
}
