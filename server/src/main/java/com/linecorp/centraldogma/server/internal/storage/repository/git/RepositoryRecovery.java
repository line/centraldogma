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

import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.R_HEADS_MASTER;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.newRevWalk;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.RepositoryRecoveryException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.repository.DiffResultType;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * Reconciles a diverged replica with a source replica, and builds the payload that carries the commits.
 */
final class RepositoryRecovery {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryRecovery.class);

    // Every replica materializes the payload in memory, so an unbounded one exhausts the cluster.
    static final int MAX_RECOVERY_COMMITS = 100;

    private final GitRepositoryManager manager;

    RepositoryRecovery(GitRepositoryManager manager) {
        this.manager = manager;
    }

    boolean recoverRepository(String repositoryName, Revision resetToRevision,
                              List<ReplayCommit> commits) {
        requireNonNull(repositoryName, "repositoryName");
        requireNonNull(resetToRevision, "resetToRevision");
        requireNonNull(commits, "commits");
        final String repoPath = manager.projectRepositoryName(repositoryName);
        logger.info("Starting to recover the repository '{}' (reset to {}, replay {} commits).",
                    repoPath, resetToRevision, commits.size());
        final long startTime = System.nanoTime();
        final GitRepository repo = fileRepository(repositoryName);
        final CommitIdDatabase commitIdDatabase = repo.commitIdDatabase();
        if (isConverged(repoPath, repo, commitIdDatabase, commits)) {
            return false;
        }

        checkResetBase(repoPath, resetToRevision, repo.normalizeNow(Revision.HEAD));
        final ObjectId resetToCommitId = commitIdDatabase.get(resetToRevision);

        // No read may observe the repository between the reset and the last replayed commit.
        repo.writeLock();
        try {
            rewindTo(repo, resetToCommitId, resetToRevision);

            // Replayed on this thread rather than through commit(), which queues to the fixed-size
            // repository worker: readers of this repository park on the write lock held here and consume
            // that pool, so a queued task can wait for a thread that only this method can release.
            for (ReplayCommit commit : commits) {
                final Revision revision = commit.revision();
                final CommitResult result = repo.blockingCommit(
                        revision.backward(1), commit.timestampMillis(), commit.author(), commit.summary(),
                        commit.detail(), commit.markup(), commit.changes());
                if (!revision.equals(result.revision())) {
                    throw new StorageException("unexpected replayed revision: " + result.revision() +
                                               " (expected: " + revision + ')');
                }
                final String expectedTreeId = commit.expectedTreeId();
                final String actualTreeId = treeIdOf(repo, commitIdDatabase.get(revision));
                if (!expectedTreeId.equals(actualTreeId)) {
                    throw new StorageException(
                            "tree id mismatch while recovering '" + repoPath + "' at " + revision +
                            " (expected: " + expectedTreeId + ", actual: " + actualTreeId +
                            "). Revisions up to " + resetToRevision + " may have diverged, or the content " +
                            "is not reproducible byte-identically (e.g. written by a content " +
                            "transformer). The recovery stopped here, leaving a partial history.");
                }
            }
        } catch (Throwable t) {
            // Deliberately not rolled back: an automatic repair is a second thing that can fail, and it
            // would leave a worse state than this one. The history stays readable, and an administrator
            // recovers it again.
            logger.error("Failed to recover the repository '{}' (reset to {}). It holds a partial history " +
                         "and must be recovered again.", repoPath, resetToRevision, t);
            throw new StorageException(
                    "failed to recover the repository '" + repoPath + "' (reset to " + resetToRevision + ')',
                    t);
        } finally {
            repo.writeUnLock();
            // The watched revisions are gone whether or not the replay finished, so make the clients ask
            // again. That drops the listeners' watches too, and a listener has no client to ask for it.
            final String cause = repoPath + " was rewritten by a recovery. Watch again.";
            repo.commitWatchers.close(() -> new RepositoryRecoveryException(cause));
            repo.rewatchListeners();
        }

        logger.info("Recovered the repository '{}' to {} in {} seconds.",
                    repoPath, repo.normalizeNow(Revision.HEAD),
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime));
        return true;
    }

    /**
     * Returns the repository to recover. An encrypted one holds its content under a per-replica key, so
     * it cannot reproduce the source's trees.
     */
    private GitRepository fileRepository(String repositoryName) {
        final GitRepository repo = (GitRepository) manager.get(repositoryName);
        if (repo.isEncrypted()) {
            throw new StorageException("recovery is not supported for an encrypted repository: " +
                                       manager.projectRepositoryName(repositoryName));
        }
        return repo;
    }

    /**
     * Returns whether the repository already holds the commits to replay, which makes recovery idempotent.
     * Every revision in the range is compared, not only the head: a tree names the content of one revision
     * and nothing before it, so a head that matches says nothing about the revisions under it - unlike a
     * commit id, which hashes its parent transitively.
     */
    private static boolean isConverged(String repoPath, GitRepository repo,
                                       CommitIdDatabase commitIdDatabase, List<ReplayCommit> commits) {
        final ReplayCommit lastCommit = commits.get(commits.size() - 1);
        final Revision currentHead = repo.normalizeNow(Revision.HEAD);
        if (!currentHead.equals(lastCommit.revision())) {
            return false;
        }
        for (ReplayCommit commit : commits) {
            final ObjectId commitId = commitIdDatabase.get(commit.revision());
            if (commitId == null || !commit.expectedTreeId().equals(treeIdOf(repo, commitId))) {
                return false;
            }
        }
        logger.info("Repository '{}' is already converged at {} (tree {}); nothing to recover.",
                    repoPath, currentHead, lastCommit.expectedTreeId());
        return true;
    }

    /**
     * Rejects a replica that never held the base the replayed commits are built on.
     */
    private static void checkResetBase(String repoPath, Revision resetToRevision, Revision headRevision) {
        if (resetToRevision.major() > headRevision.major()) {
            throw new StorageException(
                    "cannot recover " + repoPath + ": the local head " + headRevision +
                    " is below the reset revision " + resetToRevision +
                    "; this replica is missing the shared base history.");
        }
    }

    /**
     * Rejects a range the source cannot replay. Revision 1 created the repository, so it carries nothing.
     */
    private static void checkReplayRange(String repoPath, Revision fromRevision, Revision toRevision,
                                         Revision headRevision) {
        final int head = headRevision.major();
        if (head < 2) {
            throw new IllegalArgumentException(
                    "the repository has no replayable revision: " + repoPath + " (head: " + head + ')');
        }
        final int from = fromRevision.major();
        if (fromRevision.isRelative() || from < 2 || from > head) {
            throw new IllegalArgumentException(
                    "fromRevision: " + fromRevision + " (expected: an absolute revision in [2, " + head +
                    "])");
        }
        final int to = toRevision.major();
        if (toRevision.isRelative() || to < from || to > head) {
            throw new IllegalArgumentException(
                    "toRevision: " + toRevision + " (expected: an absolute revision in [" + from + ", " +
                    head + "])");
        }
    }

    /**
     * Returns the ID of the tree a commit points at - the fingerprint of the content alone. A commit ID
     * covers the parent and the timestamp as well, and a metadata repository writes its early commits
     * locally on each replica, so replicas holding identical content still report different commit IDs.
     */
    private static String treeIdOf(GitRepository repo, ObjectId commitId) {
        try (RevWalk revWalk = newRevWalk(repo.jGitRepository().newObjectReader())) {
            return revWalk.parseCommit(commitId).getTree().getId().name();
        } catch (IOException e) {
            throw new StorageException("failed to read the tree of " + commitId.name(), e);
        }
    }

    /**
     * Discards everything after {@code revision} in place, including whatever was cached from it.
     */
    private static void rewindTo(GitRepository repo, ObjectId commitId, Revision revision) {
        final org.eclipse.jgit.lib.Repository jGitRepository = repo.jGitRepository();
        try (RevWalk revWalk = newRevWalk(jGitRepository.newObjectReader())) {
            final RefUpdate refUpdate = jGitRepository.updateRef(R_HEADS_MASTER);
            refUpdate.setNewObjectId(commitId);
            refUpdate.setForceUpdate(true);
            final Result result = refUpdate.update(revWalk);
            switch (result) {
                case NEW:
                case FAST_FORWARD:
                case FORCED:
                case NO_CHANGE:
                    break;
                default:
                    throw new StorageException("unexpected forced refUpdate state: " + result);
            }
        } catch (IOException e) {
            throw new StorageException("failed to move " + R_HEADS_MASTER + " back to " + commitId.name(), e);
        }
        repo.commitIdDatabase().truncateTo(revision);
        repo.setHeadRevision(revision);
        repo.nextCacheGeneration();
    }

    List<ReplayCommit> buildRecoveryPayload(String repositoryName, Revision fromRevision,
                                                  Revision toRevision) {
        requireNonNull(repositoryName, "repositoryName");
        requireNonNull(fromRevision, "fromRevision");
        requireNonNull(toRevision, "toRevision");
        final String repoPath = manager.projectRepositoryName(repositoryName);
        final GitRepository repo = fileRepository(repositoryName);
        final CommitIdDatabase commitIdDatabase = repo.commitIdDatabase();
        final Revision headRevision = repo.normalizeNow(Revision.HEAD);
        checkReplayRange(repoPath, fromRevision, toRevision, headRevision);

        final int from = fromRevision.major();
        final int to = toRevision.major();
        final int commitCount = to - from + 1;
        checkCommitCount(repoPath, commitCount);

        // The whole range in one walk, and blockingHistory() takes the count as given.
        final List<Commit> history =
                repo.blockingHistory(fromRevision, toRevision, Repository.ALL_PATH, commitCount);
        if (history.size() != commitCount) {
            throw new StorageException("expected " + commitCount + " commits of " + repoPath + " in " +
                                       fromRevision + ".." + toRevision + ", but got " + history.size());
        }

        final ImmutableList.Builder<ReplayCommit> commits =
                ImmutableList.builderWithExpectedSize(commitCount);
        for (int i = from; i <= to; i++) {
            final Revision revision = new Revision(i);
            final Commit commit = history.get(i - from);
            final Map<String, Change<?>> changes =
                    repo.diff(revision.backward(1), revision, Repository.ALL_PATH,
                              DiffResultType.PATCH_TO_TEXT_UPSERT).join();
            commits.add(new ReplayCommit(revision, commit.when(), commit.author(), commit.summary(),
                                         commit.detail(), commit.markup(), changes.values(),
                                         treeIdOf(repo, commitIdDatabase.get(revision))));
        }
        return commits.build();
    }

    static void checkCommitCount(String name, int commitCount) {
        if (commitCount > MAX_RECOVERY_COMMITS) {
            throw new IllegalArgumentException(
                    "the recovery of " + name + " spans too many revisions: " + commitCount +
                    " (maximum: " + MAX_RECOVERY_COMMITS + "). Narrow the range.");
        }
    }
}
