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

import static com.linecorp.centraldogma.internal.Util.deleteFileTree;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.R_HEADS_MASTER;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.closeRepository;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.deleteCruft;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.newRevWalk;
import static java.util.Objects.requireNonNull;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_REPO_FORMAT_VERSION;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.internal.JGitUtil;
import com.linecorp.centraldogma.server.internal.storage.DirectoryBasedStorageManager;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.RocksDbCommitIdDatabase;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.RocksDbRepository;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.DiffResultType;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryListener;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

public final class GitRepositoryManager extends DirectoryBasedStorageManager<Repository>
        implements RepositoryManager {

    private static final Logger logger = LoggerFactory.getLogger(GitRepositoryManager.class);

    private static final String ENCRYPTED_REPO_PLACEHOLDER_FILE = ".encryption-repo-placeholder";

    private final Project parent;
    private final Executor repositoryWorker;

    @Nullable
    private final RepositoryCache cache;
    @Nullable
    private volatile BiConsumer<String, Repository> postMigrationCallback;

    public GitRepositoryManager(Project parent, File rootDir, Executor repositoryWorker,
                                Executor purgeWorker, @Nullable RepositoryCache cache,
                                EncryptionStorageManager encryptionStorageManager) {
        super(rootDir, Repository.class, purgeWorker, encryptionStorageManager);
        this.parent = requireNonNull(parent, "parent");
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
        this.cache = cache;
        init();
    }

    @Override
    public Project parent() {
        return parent;
    }

    @Override
    public void setPostMigrationCallback(BiConsumer<String, Repository> callback) {
        postMigrationCallback = callback;
    }

    private String projectRepositoryName(String name) {
        return parent.name() + '/' + name;
    }

    @Override
    public void migrateToEncryptedRepository(String repositoryName) {
        logger.info("Starting to migrate the repository '{}' to an encrypted repository.",
                    projectRepositoryName(repositoryName));
        final long startTime = System.nanoTime();
        final Repository oldRepository = get(repositoryName);

        final GitRepository encryptedRepository;
        final EncryptionStorageManager encryptionStorageManager = encryptionStorageManager();
        try {
            encryptedRepository = createEncryptedRepository(parent, oldRepository.repoDir(),
                                                            oldRepository.author(),
                                                            oldRepository.creationTimeMillis(),
                                                            repositoryWorker, cache,
                                                            encryptionStorageManager);
        } catch (Throwable t) {
            throw new StorageException("failed to create the repository while migrating. " +
                                       "repositoryName: " + projectRepositoryName(repositoryName), t);
        }

        final Revision headRevision = oldRepository.normalizeNow(Revision.HEAD);
        Revision baseRevision = null;
        try {
            for (int i = 2; i <= headRevision.major(); i++) {
                baseRevision = new Revision(i - 1);
                final Revision revision = new Revision(i);
                final CompletableFuture<List<Commit>> historyFuture =
                        oldRepository.history(revision, revision, "/**");
                final CompletableFuture<Map<String, Change<?>>> changesFuture =
                        oldRepository.diff(baseRevision, revision, "/**", DiffResultType.PATCH_TO_TEXT_UPSERT);
                CompletableFuture.allOf(historyFuture, changesFuture).join();
                final List<Commit> commits = historyFuture.join();
                assert commits.size() == 1;
                final Commit commit = commits.get(0);
                encryptedRepository.commit(baseRevision, commit.when(), commit.author(), commit.summary(),
                                           changesFuture.join().values()).join();
            }
        } catch (Throwable t) {
            encryptedRepository.internalClose();
            encryptionStorageManager.deleteRepositoryData(parent.name(), repositoryName);
            throw new StorageException("failed to migrate the contents of the repository '" +
                                       projectRepositoryName(repositoryName) + "' to an encrypted repository." +
                                       " baseRevision: " + baseRevision, t);
        }

        // Simply create the placeholder file to indicate that this repository is encrypted.
        // TODO(minwoox): remove the old content of the repository using a plugin.
        try {
            Files.createFile(Paths.get(oldRepository.repoDir().getPath(), ENCRYPTED_REPO_PLACEHOLDER_FILE));
        } catch (IOException e) {
            encryptedRepository.internalClose();
            encryptionStorageManager.deleteRepositoryData(parent.name(), repositoryName);
            throw new StorageException("failed to create the encrypted repository placeholder file at: " +
                                       oldRepository.repoDir(), e);
        }

        if (!replaceChild(repositoryName, oldRepository, encryptedRepository)) {
            encryptedRepository.internalClose();
            encryptionStorageManager.deleteRepositoryData(parent.name(), repositoryName);
            try {
                Files.delete(Paths.get(oldRepository.repoDir().getPath(), ENCRYPTED_REPO_PLACEHOLDER_FILE));
            } catch (IOException e) {
                logger.warn("Failed to delete the encrypted repository placeholder file at: {}",
                            oldRepository.repoDir(), e);
            }
            throw new StorageException("failed to replace the old repository with the encrypted repository. " +
                                       "repositoryName: " + projectRepositoryName(repositoryName));
        }
        logger.info("Migrated the repository '{}' to an encrypted repository in {} seconds.",
                    projectRepositoryName(repositoryName),
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime));
        // Transfer listeners from the old repository to the new encrypted repository.
        for (RepositoryListener listener : ((GitRepository) oldRepository).listeners()) {
            encryptedRepository.addListener(listener);
        }
        final BiConsumer<String, Repository> callback = postMigrationCallback;
        if (callback != null) {
            callback.accept(repositoryName, encryptedRepository);
        }
        ((GitRepository) oldRepository).close(() -> new CentralDogmaException(
                projectRepositoryName(repositoryName) + " is migrated to an encrypted repository. Try again."));
    }

    @Override
    public void fallbackToFileRepository(String repositoryName) {
        logger.info("Starting to fallback the repository '{}' to a file-based repository.",
                    projectRepositoryName(repositoryName));
        final long startTime = System.nanoTime();
        final Repository encryptedRepository = get(repositoryName);
        final File repoDir = encryptedRepository.repoDir();

        final Path placeholderPath = Paths.get(repoDir.getPath(), ENCRYPTED_REPO_PLACEHOLDER_FILE);
        if (!encryptedRepository.isEncrypted() || !Files.exists(placeholderPath) || !exist(repoDir)) {
            throw new StorageException("repository has no preserved file-based repository to fall back to: " +
                                       projectRepositoryName(repositoryName));
        }

        // Delete the placeholder file so that the original file-based git data is recognized again.
        // migrateToEncryptedRepository() preserves the original git files in repoDir,
        // so we can simply reopen the existing file-based repository.
        try {
            Files.delete(placeholderPath);
        } catch (IOException e) {
            throw new StorageException("failed to delete the encrypted repository placeholder file at: " +
                                       repoDir, e);
        }

        // Reopen the existing file-based git repository from repoDir.
        final GitRepository fileRepository;
        try {
            fileRepository = openFileRepository(parent, repoDir, repositoryWorker, cache);
        } catch (Throwable t) {
            // Restore the placeholder file so the manager stays in a consistent state.
            try {
                Files.createFile(Paths.get(repoDir.getPath(), ENCRYPTED_REPO_PLACEHOLDER_FILE));
            } catch (IOException ex) {
                logger.warn("Failed to restore the encrypted repository placeholder file at: {}",
                            repoDir, ex);
            }
            throw new StorageException("failed to reopen the file-based repository after fallback. " +
                                       "repositoryName: " + projectRepositoryName(repositoryName), t);
        }

        if (!replaceChild(repositoryName, encryptedRepository, fileRepository)) {
            fileRepository.internalClose();
            try {
                Files.createFile(Paths.get(repoDir.getPath(), ENCRYPTED_REPO_PLACEHOLDER_FILE));
            } catch (IOException ex) {
                logger.warn("Failed to restore the encrypted repository placeholder file at: {}",
                            repoDir, ex);
            }
            throw new StorageException(
                    "failed to replace the encrypted repository with the file-based repository. " +
                    "repositoryName: " + projectRepositoryName(repositoryName));
        }

        logger.info("Fallback the repository '{}' to a file-based repository in {} seconds.",
                    projectRepositoryName(repositoryName),
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime));
        // Transfer listeners from the encrypted repository to the file-based repository.
        for (RepositoryListener listener : ((GitRepository) encryptedRepository).listeners()) {
            fileRepository.addListener(listener);
        }
        final BiConsumer<String, Repository> callback = postMigrationCallback;
        if (callback != null) {
            callback.accept(repositoryName, fileRepository);
        }
        ((GitRepository) encryptedRepository).close(() -> new CentralDogmaException(
                projectRepositoryName(repositoryName) +
                " is fallback to a file-based repository. Try again."));
        try {
            encryptionStorageManager().deleteRepositoryData(parent.name(), repositoryName);
        } catch (Throwable t) {
            logger.warn("Failed to delete the encrypted repository data for the repository '{}' " +
                        "after fallback. ", projectRepositoryName(repositoryName), t);
        }
    }

    @Override
    public void recoverRepository(String repositoryName, Revision resetToRevision, List<ReplayCommit> commits) {
        requireNonNull(repositoryName, "repositoryName");
        requireNonNull(resetToRevision, "resetToRevision");
        requireNonNull(commits, "commits");
        logger.info("Starting to recover the repository '{}' (reset to {}, replay {} commits).",
                    projectRepositoryName(repositoryName), resetToRevision, commits.size());
        final long startTime = System.nanoTime();
        final GitRepository old = (GitRepository) get(repositoryName);
        if (old.isEncrypted()) {
            throw new StorageException("recovery is not supported for an encrypted repository: " +
                                       projectRepositoryName(repositoryName));
        }
        if (commits.isEmpty()) {
            return;
        }

        // Skip if the repository is already converged with the source (the source replica itself, or a
        // healthy replica): its HEAD revision and commit id already match the last replayed commit. This
        // keeps the source untouched and makes recovery idempotent.
        final ReplayCommit lastCommit = commits.get(commits.size() - 1);
        final String expectedHeadCommitId = lastCommit.expectedCommitId();
        final Revision currentHead = old.normalizeNow(Revision.HEAD);
        if (expectedHeadCommitId != null && currentHead.equals(lastCommit.revision())) {
            final ObjectId currentHeadCommitId = old.commitIdDatabase().get(currentHead);
            if (currentHeadCommitId != null && expectedHeadCommitId.equals(currentHeadCommitId.name())) {
                logger.debug("Repository '{}' is already at {} ({}); skipping recovery.",
                             projectRepositoryName(repositoryName), currentHead, expectedHeadCommitId);
                return;
            }
        }

        // Remember the current HEAD so the old repository can be restored if recovery fails.
        final Revision originalHeadRevision = old.normalizeNow(Revision.HEAD);
        if (resetToRevision.major() > originalHeadRevision.major()) {
            throw new StorageException(
                    "cannot recover " + projectRepositoryName(repositoryName) + ": the local head " +
                    originalHeadRevision + " is below the reset revision " + resetToRevision +
                    "; this replica is missing the shared base history.");
        }
        final ObjectId originalHeadCommitId = old.commitIdDatabase().get(originalHeadRevision);
        final ObjectId resetToCommitId = old.commitIdDatabase().get(resetToRevision);
        final File repoDir = old.repoDir();

        // Quiesce the old repository so no read observes a partially rebuilt commit-id database while the new
        // repository is opened on the same directory.
        old.writeLock();
        GitRepository neo = null;
        boolean swapped = false;
        try {
            // Force-move master backward to the reset revision, then reopen so that openFileRepository()
            // rebuilds the commit-id database to match the new HEAD.
            forceMoveMaster(old.jGitRepository(), resetToCommitId);
            neo = openFileRepository(parent, repoDir, repositoryWorker, cache);

            // Replay the source commits so every replica converges to the same commit ids.
            for (ReplayCommit commit : commits) {
                final Revision revision = commit.revision();
                final CommitResult result = neo.commit(
                        revision.backward(1), commit.timestampMillis(), commit.author(), commit.summary(),
                        commit.detail(), commit.markup(), commit.changes(), false).join();
                if (!revision.equals(result.revision())) {
                    throw new StorageException("unexpected replayed revision: " + result.revision() +
                                               " (expected: " + revision + ')');
                }
                final String expectedCommitId = commit.expectedCommitId();
                if (expectedCommitId != null) {
                    final String actualCommitId = neo.commitIdDatabase().get(revision).name();
                    if (!expectedCommitId.equals(actualCommitId)) {
                        throw new StorageException(
                                "commit id mismatch while recovering '" +
                                projectRepositoryName(repositoryName) + "' at " + revision + " (expected: " +
                                expectedCommitId + ", actual: " + actualCommitId + "). Revisions up to " +
                                resetToRevision + " may have diverged, or the content is not reproducible " +
                                "byte-identically (e.g. written by a content transformer); the repository " +
                                "was rolled back.");
                    }
                }
            }

            if (!replaceChild(repositoryName, old, neo)) {
                throw new StorageException("failed to replace the repository after recovery: " +
                                           projectRepositoryName(repositoryName));
            }
            swapped = true;
            // The old instance shares the on-disk state that was just rewritten, so a reader that was
            // blocked on its lock must fail fast instead of reading through the stale instance.
            old.markClosePending(() -> new CentralDogmaException(
                    projectRepositoryName(repositoryName) + " is recovered. Try again."));
        } catch (Throwable t) {
            throw new StorageException("failed to recover the repository '" +
                                       projectRepositoryName(repositoryName) + "' (reset to " +
                                       resetToRevision + ')', t);
        } finally {
            if (!swapped) {
                // Roll back so the (still diverged) old repository stays internally consistent.
                if (neo != null) {
                    try {
                        neo.internalClose();
                    } catch (Throwable t2) {
                        logger.warn("Failed to close the partially recovered repository '{}'.",
                                    projectRepositoryName(repositoryName), t2);
                    }
                }
                try {
                    forceMoveMaster(old.jGitRepository(), originalHeadCommitId);
                    old.commitIdDatabase().rebuild(old.jGitRepository());
                    old.setHeadRevision(originalHeadRevision);
                } catch (Throwable t2) {
                    logger.error("Failed to roll back the repository '{}' after a failed recovery.",
                                 projectRepositoryName(repositoryName), t2);
                }
            }
            old.writeUnLock();
        }

        // Transfer listeners from the old repository to the recovered one, then close the old repository.
        for (RepositoryListener listener : old.listeners()) {
            neo.addListener(listener);
        }
        final BiConsumer<String, Repository> callback = postMigrationCallback;
        if (callback != null) {
            callback.accept(repositoryName, neo);
        }
        old.close(() -> new CentralDogmaException(
                projectRepositoryName(repositoryName) + " is recovered. Try again."));
        logger.info("Recovered the repository '{}' to {} in {} seconds.",
                    projectRepositoryName(repositoryName), neo.normalizeNow(Revision.HEAD),
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime));
    }

    private static void forceMoveMaster(org.eclipse.jgit.lib.Repository jGitRepository, ObjectId commitId) {
        try (RevWalk revWalk = newRevWalk(jGitRepository.newObjectReader())) {
            GitRepository.doForceRefUpdate(jGitRepository, revWalk, R_HEADS_MASTER, commitId);
        } catch (IOException e) {
            throw new StorageException("failed to force-move " + R_HEADS_MASTER + " to " + commitId.name(), e);
        }
    }

    @Override
    public List<ReplayCommit> buildRecoveryPayload(String repositoryName, Revision fromRevision) {
        requireNonNull(repositoryName, "repositoryName");
        requireNonNull(fromRevision, "fromRevision");
        final GitRepository repo = (GitRepository) get(repositoryName);
        if (repo.isEncrypted()) {
            throw new StorageException("recovery is not supported for an encrypted repository: " +
                                       projectRepositoryName(repositoryName));
        }
        final Revision headRevision = repo.normalizeNow(Revision.HEAD);
        if (headRevision.major() < 2) {
            throw new IllegalArgumentException(
                    "the repository has no replayable revision: " + projectRepositoryName(repositoryName) +
                    " (head: " + headRevision + ')');
        }
        final int from = fromRevision.major();
        if (fromRevision.isRelative() || from < 2 || from > headRevision.major()) {
            throw new IllegalArgumentException(
                    "fromRevision: " + fromRevision + " (expected: an absolute revision in [2, " +
                    headRevision.major() + "])");
        }

        final int commitCount = headRevision.major() - from + 1;
        validateRecoveryPayloadSize(projectRepositoryName(repositoryName), commitCount, 0);
        long payloadBytes = 0;
        final ImmutableList.Builder<ReplayCommit> commits =
                ImmutableList.builderWithExpectedSize(commitCount);
        for (int i = from; i <= headRevision.major(); i++) {
            final Revision revision = new Revision(i);
            final Commit commit = repo.history(revision, revision, Repository.ALL_PATH).join().get(0);
            final Map<String, Change<?>> changes =
                    repo.diff(revision.backward(1), revision, Repository.ALL_PATH,
                              DiffResultType.PATCH_TO_TEXT_UPSERT).join();
            for (Change<?> change : changes.values()) {
                payloadBytes += estimateSize(change);
            }
            validateRecoveryPayloadSize(projectRepositoryName(repositoryName), commitCount, payloadBytes);
            commits.add(new ReplayCommit(revision, commit.when(), commit.author(), commit.summary(),
                                         commit.detail(), commit.markup(), changes.values(),
                                         repo.commitIdDatabase().get(revision).name()));
        }
        return commits.build();
    }

    // The payload crosses the replication log as a single entry and is materialized in memory by every
    // replica, so an unbounded payload could exhaust the heap cluster-wide and stall replication.
    @VisibleForTesting
    static final int MAX_RECOVERY_COMMITS = 10_000;
    @VisibleForTesting
    static final long MAX_RECOVERY_PAYLOAD_BYTES = 64 * 1024 * 1024;

    @VisibleForTesting
    static void validateRecoveryPayloadSize(String name, int commitCount, long payloadBytes) {
        if (commitCount > MAX_RECOVERY_COMMITS) {
            throw new IllegalArgumentException(
                    "the recovery of " + name + " spans too many revisions: " + commitCount +
                    " (maximum: " + MAX_RECOVERY_COMMITS + "). Recover from a later revision.");
        }
        if (payloadBytes > MAX_RECOVERY_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "the recovery payload of " + name + " is larger than " + MAX_RECOVERY_PAYLOAD_BYTES +
                    " bytes. Recover from a later revision.");
        }
    }

    private static long estimateSize(Change<?> change) {
        final Object content = change.content();
        long size = change.path().length();
        if (content instanceof String) {
            size += ((String) content).length();
        } else if (content != null) {
            size += content.toString().length();
        }
        return size;
    }

    @Override
    protected Repository openChild(File childDir) throws Exception {
        requireNonNull(childDir, "childDir");
        if (isEncryptedRepository(childDir)) {
            return openEncryptionRepository(
                    parent, childDir, repositoryWorker, cache, encryptionStorageManager());
        } else {
            return openFileRepository(parent, childDir, repositoryWorker, cache);
        }
    }

    public static boolean isEncryptedRepository(File dir) {
        return Files.exists(dir.toPath().resolve(ENCRYPTED_REPO_PLACEHOLDER_FILE));
    }

    @VisibleForTesting
    static Repository openEncryptionRepository(Project parent, File repoDir, Executor repositoryWorker,
                                               @Nullable RepositoryCache cache,
                                               EncryptionStorageManager encryptionStorageManager) {
        final EncryptionGitStorage encryptionGitStorage =
                new EncryptionGitStorage(parent.name(), repoDir.getName(), encryptionStorageManager);
        final RocksDbRepository rocksDbRepository = new RocksDbRepository(encryptionGitStorage);
        final Revision headRevision = uncachedHeadRevision(rocksDbRepository, parent, repoDir);
        final RocksDbCommitIdDatabase commitIdDatabase =
                new RocksDbCommitIdDatabase(encryptionGitStorage, headRevision);
        return new GitRepository(parent, repoDir, repositoryWorker, cache, rocksDbRepository,
                                 commitIdDatabase, headRevision);
    }

    @VisibleForTesting
    static GitRepository openFileRepository(Project parent, File repoDir, Executor repositoryWorker,
                                            @Nullable RepositoryCache cache) {
        final org.eclipse.jgit.lib.Repository jGitRepository;
        try {
            jGitRepository = new RepositoryBuilder().setGitDir(repoDir).setBare().build();
            if (!exist(repoDir)) {
                throw RepositoryNotFoundException.of(parent.name(), repoDir.getName());
            }

            // Retrieve the repository format.
            final int formatVersion = jGitRepository.getConfig().getInt(
                    CONFIG_CORE_SECTION, null, CONFIG_KEY_REPO_FORMAT_VERSION, 0);
            if (formatVersion != JGitUtil.REPO_FORMAT_VERSION) {
                throw new StorageException("unsupported repository format version: " + formatVersion +
                                           " (expected: " + JGitUtil.REPO_FORMAT_VERSION + ')');
            }

            // Update the default settings if necessary.
            JGitUtil.applyDefaultsAndSave(jGitRepository.getConfig());
        } catch (IOException e) {
            throw new StorageException("failed to open a repository at: " + repoDir, e);
        }

        CommitIdDatabase commitIdDatabase = null;
        boolean success = false;
        try {
            final Revision headRevision = uncachedHeadRevision(jGitRepository, parent, repoDir);
            commitIdDatabase = new DefaultCommitIdDatabase(jGitRepository);
            if (!headRevision.equals(commitIdDatabase.headRevision())) {
                commitIdDatabase.rebuild(jGitRepository);
                assert headRevision.equals(commitIdDatabase.headRevision());
            }
            final GitRepository gitRepository = new GitRepository(parent, repoDir, repositoryWorker, cache,
                                                                  jGitRepository,
                                                                  commitIdDatabase, headRevision);
            success = true;
            return gitRepository;
        } finally {
            if (!success) {
                closeRepository(commitIdDatabase, jGitRepository);
            }
        }
    }

    private static Revision uncachedHeadRevision(org.eclipse.jgit.lib.Repository jGitRepository, Project parent,
                                                 File repoDir) {
        try (RevWalk revWalk = newRevWalk(jGitRepository.newObjectReader())) {
            final ObjectId headRevisionId = jGitRepository.resolve(R_HEADS_MASTER);
            if (headRevisionId != null) {
                final RevCommit revCommit = revWalk.parseCommit(headRevisionId);
                return CommitUtil.extractRevision(revCommit.getFullMessage());
            }
        } catch (CentralDogmaException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to get the current revision", e);
        }

        throw new StorageException("failed to determine the HEAD: " + parent.name() + '/' + repoDir.getName());
    }

    @Override
    protected Repository createChild(
            File childDir, Author author, long creationTimeMillis, boolean encrypt) throws Exception {
        requireNonNull(childDir, "childDir");
        requireNonNull(author, "author");
        if (encrypt) {
            return createEncryptionRepository(parent, childDir, author, creationTimeMillis,
                                              repositoryWorker, cache, encryptionStorageManager());
        } else {
            return createFileRepository(parent, childDir, author, creationTimeMillis, repositoryWorker, cache);
        }
    }

    @VisibleForTesting
    static GitRepository createEncryptionRepository(
            Project parent, File repoDir, Author author, long creationTimeMillis,
            Executor repositoryWorker, @Nullable RepositoryCache cache,
            EncryptionStorageManager encryptionStorageManager) throws IOException {
        if (!repoDir.mkdirs()) {
            throw new StorageException(
                    "failed to create a repository at: " + repoDir + " (exists already)");
        }
        try {
            Files.createFile(Paths.get(repoDir.getPath(), ENCRYPTED_REPO_PLACEHOLDER_FILE));
            return createEncryptedRepository(parent, repoDir, author, creationTimeMillis, repositoryWorker,
                                             cache, encryptionStorageManager);
        } catch (Throwable t) {
            deleteCruft(repoDir);
            throw new StorageException("failed to create a repository at: " + repoDir, t);
        }
    }

    private static GitRepository createEncryptedRepository(
            Project parent, File repoDir, Author author,
            long creationTimeMillis, Executor repositoryWorker,
            @Nullable RepositoryCache cache,
            EncryptionStorageManager encryptionStorageManager) throws IOException {
        final EncryptionGitStorage encryptionGitStorage =
                new EncryptionGitStorage(parent.name(), repoDir.getName(), encryptionStorageManager);
        final RocksDbRepository rocksDbRepository = new RocksDbRepository(encryptionGitStorage);
        // Initialize the master branch.
        final RefUpdate head = rocksDbRepository.updateRef(Constants.HEAD);
        head.disableRefLog();
        head.link(R_HEADS_MASTER);
        final RocksDbCommitIdDatabase commitIdDatabase =
                new RocksDbCommitIdDatabase(encryptionGitStorage, null);
        return new GitRepository(parent, repoDir, repositoryWorker,
                                 creationTimeMillis, author, cache,
                                 rocksDbRepository, commitIdDatabase);
    }

    @VisibleForTesting
    static GitRepository createFileRepository(
            Project parent, File repoDir, Author author, long creationTimeMillis, Executor repositoryWorker,
            @Nullable RepositoryCache cache) throws IOException {
        org.eclipse.jgit.lib.Repository jGitRepository = null;
        CommitIdDatabase commitIdDatabase = null;
        try {
            final RepositoryBuilder repositoryBuilder = new RepositoryBuilder().setGitDir(repoDir).setBare();
            // Create an empty repository with format version 0 first.
            try (org.eclipse.jgit.lib.Repository initRepo = repositoryBuilder.build()) {
                if (exist(repoDir)) {
                    throw new StorageException(
                            "failed to create a repository at: " + repoDir + " (exists already)");
                }
                initRepo.create(true);

                // Save the initial default settings.
                JGitUtil.applyDefaultsAndSave(initRepo.getConfig());
            }

            // Re-open the repository with the updated settings.
            jGitRepository = new RepositoryBuilder().setGitDir(repoDir).build();

            // Initialize the master branch.
            final RefUpdate head = jGitRepository.updateRef(Constants.HEAD);
            head.disableRefLog();
            head.link(R_HEADS_MASTER);
            commitIdDatabase = new DefaultCommitIdDatabase(jGitRepository);
            return new GitRepository(parent, repoDir, repositoryWorker,
                                     creationTimeMillis, author, cache,
                                     jGitRepository, commitIdDatabase);
        } catch (Throwable t) {
            closeRepository(commitIdDatabase, jGitRepository);
            // Failed to create a repository. Remove any cruft so that it is not loaded on the next run.
            deleteCruft(repoDir);
            throw new StorageException("failed to create a repository at: " + repoDir, t);
        }
    }

    private static boolean exist(File repoDir) {
        try {
            final RepositoryBuilder repositoryBuilder = new RepositoryBuilder().setGitDir(repoDir);
            final org.eclipse.jgit.lib.Repository repository = repositoryBuilder.build();
            if (repository.getConfig() instanceof FileBasedConfig) {
                return ((FileBasedConfig) repository.getConfig()).getFile().exists();
            }
            return repository.getDirectory().exists();
        } catch (IOException e) {
            throw new StorageException("failed to check if repository exists at " + repoDir, e);
        }
    }

    @Override
    protected void closeChild(File childDir, Repository child,
                              Supplier<CentralDogmaException> failureCauseSupplier) {
        ((GitRepository) child).close(failureCauseSupplier);
    }

    @Override
    protected CentralDogmaException newStorageExistsException(String name) {
        return RepositoryExistsException.of(parent().name(), name);
    }

    @Override
    protected CentralDogmaException newStorageNotFoundException(String name) {
        return RepositoryNotFoundException.of(parent().name(), name);
    }

    @Override
    protected void deletePurged(File file) {
        final String repoName = removeInterfixAndPurgedSuffix(file.getName());
        logger.info("Deleting a purged repository: {} ..", repoName);
        if (isEncryptedRepository(file)) {
            encryptionStorageManager().deleteRepositoryData(parent.name(), repoName);
            // Then remove the directory below.
        }
        try {
            deleteFileTree(file);
            logger.info("Deleted a purged repository: {}.", repoName);
        } catch (IOException e) {
            logger.warn("Failed to delete a purged repository: {}", repoName, e);
        }
    }

    public static String removeInterfixAndPurgedSuffix(String name) {
        assert name.endsWith(SUFFIX_PURGED);
        name = name.substring(0, name.length() - SUFFIX_PURGED.length());
        final int index = name.lastIndexOf('.');
        assert index > 0;
        return name.substring(0, index);
    }
}
