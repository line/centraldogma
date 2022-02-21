/*
 * Copyright 2021 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.centraldogma.server.internal.storage.DirectoryBasedStorageManager.SUFFIX_REMOVED;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.FailFastUtil.context;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.FailFastUtil.failFastIfTimedOut;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.closeJGitRepo;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.InternalRepository.buildJGitRepo;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.internal.storage.repository.git.InternalRepository.RevisionAndEntries;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.FindOption;
import com.linecorp.centraldogma.server.storage.repository.FindOptions;

class GitRepositoryV2 implements com.linecorp.centraldogma.server.storage.repository.Repository {

    private static final Logger logger = LoggerFactory.getLogger(GitRepositoryV2.class);

    static final String R_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;

    /**
     * Opens an existing Git-backed repository.
     *
     * @param repositoryDir the location of this repository
     * @param repositoryWorker the {@link Executor} which will perform the blocking repository operations
     *
     * @throws StorageException if failed to open the repository at the specified location
     */
    static GitRepositoryV2 open(Project parent, File repositoryDir,
                                Executor repositoryWorker, @Nullable RepositoryCache cache) {
        return new GitRepositoryV2(parent, repositoryDir, repositoryWorker, cache);
    }

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Project parent;
    private final String originalRepoName;
    private final Executor repositoryWorker;

    private final RepositoryMetadataDatabase repoMetadata;
    @Nullable
    @VisibleForTesting
    final RepositoryCache cache;
    @VisibleForTesting
    final CommitWatchers commitWatchers = new CommitWatchers();
    private final AtomicReference<Supplier<CentralDogmaException>> closePending = new AtomicReference<>();
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    @VisibleForTesting
    InternalRepository primaryRepo;

    @Nullable
    @VisibleForTesting
    InternalRepository secondaryRepo;

    private final long creationTimeMillis;
    private final Author author;

    /**
     * The current head revision. Initialized by the constructor and updated by commit().
     */
    private volatile Revision headRevision;

    GitRepositoryV2(Project parent, File repositoryDir, Executor repositoryWorker,
                    long creationTimeMillis, Author author, @Nullable RepositoryCache cache) {
        this.parent = requireNonNull(parent, "parent");
        originalRepoName = requireNonNull(repositoryDir, "repositoryDir").getName();
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
        this.cache = cache;

        requireNonNull(author, "author");
        try {
            if (repositoryDir.exists()) {
                if (!isEmpty(repositoryDir)) {
                    throw new StorageException("failed to create a repository at: " + repositoryDir +
                                               " (exists already)");
                }
            } else if (!repositoryDir.mkdir()) {
                throw new StorageException("failed to create a repository at: " + repositoryDir);
            }
        } catch (IOException e) {
            throw new StorageException("failed to create a repository at: " + repositoryDir, e);
        }

        repoMetadata = new RepositoryMetadataDatabase(repositoryDir, true);
        final File primaryRepoDir = repoMetadata.primaryRepoDir();
        try {
            primaryRepo = InternalRepository.of(parent, originalRepoName, primaryRepoDir, Revision.INIT,
                                                creationTimeMillis, author, ImmutableList.of());
        } catch (Throwable t) {
            repoMetadata.close();
            throw t;
        }
        headRevision = Revision.INIT;
        this.creationTimeMillis = creationTimeMillis;
        this.author = author;
    }

    private static boolean isEmpty(File dir) throws IOException {
        if (!dir.isDirectory()) {
            return false;
        }
        if (!dir.exists()) {
            return true;
        }
        try (Stream<Path> entries = Files.list(dir.toPath())) {
            return entries.findFirst().isPresent();
        }
    }

    private GitRepositoryV2(Project parent, File repoDir, Executor repositoryWorker,
                            @Nullable RepositoryCache cache) {
        this.parent = requireNonNull(parent, "parent");
        originalRepoName = requireNonNull(repoDir, "repoDir").getName();
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
        this.cache = cache;

        RepositoryMetadataDatabase repoMetadata;
        try {
            repoMetadata = new RepositoryMetadataDatabase(repoDir, false);
        } catch (Throwable t) {
            // The metadata doesn't exist so check if the repository exists in the form of the old version.
            checkRepositoryExists(repoDir);
            migrateToV2(repoDir);
            repoMetadata = new RepositoryMetadataDatabase(repoDir, true);
        }

        this.repoMetadata = repoMetadata;
        try {
            final InternalRepository primaryRepo =
                    InternalRepository.open(parent, originalRepoName, repoMetadata.primaryRepoDir(), true);
            assert primaryRepo != null;
            this.primaryRepo = primaryRepo;
            headRevision = primaryRepo.headRevision();
            final Commit initialCommit = currentInitialCommit(primaryRepo);
            creationTimeMillis = initialCommit.when();
            author = initialCommit.author();
            secondaryRepo = InternalRepository.open(parent, originalRepoName,
                                                    repoMetadata.secondaryRepoDir(), false);
        } catch (Throwable t) {
            repoMetadata.close();
            closeInternalRepository(primaryRepo);
            closeInternalRepository(secondaryRepo);
            throw t;
        }
    }

    private static void checkRepositoryExists(File repoDir) {
        final Repository oldRepo = buildJGitRepo(repoDir);
        final boolean oldRepoExist = GitRepositoryUtil.exists(oldRepo);
        closeJGitRepo(oldRepo);
        if (!oldRepoExist) {
            throw new RepositoryNotFoundException(repoDir.toString());
        }
    }

    private static void migrateToV2(File repoDir) {
        // When:
        // - repoDir: /foo
        // - primaryRepoDir: /foo/foo_0000000000
        // - tmpRepoDir: /bar
        //
        // Migration steps will be:
        // - /foo becomes /bar
        // - /foo/foo_0000000000 directory is created.
        // - /bar becomes /foo/foo_0000000000

        final File primaryRepoDir = RepositoryMetadataDatabase.initialPrimaryRepoDir(repoDir);
        final File tmpRepoDir = new File(repoDir.getParentFile(), UUID.randomUUID().toString());
        logger.debug("Migrating {} to {} using temp repository: {}", repoDir, primaryRepoDir, tmpRepoDir);
        if (!repoDir.renameTo(tmpRepoDir)) {
            throw new StorageException("failed to migrate a repository at: " + repoDir +
                                       ", to the tmp dir: " + tmpRepoDir);
        }
        if (!primaryRepoDir.mkdirs()) {
            throw new StorageException("failed to create " + primaryRepoDir + " while migrating to V2.");
        }
        try {
            final Path moved = Files.move(tmpRepoDir.toPath(), primaryRepoDir.toPath(), REPLACE_EXISTING);
            assert moved == primaryRepoDir.toPath();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmpRepoDir.renameTo(repoDir);
            throw new StorageException("failed to migrate a repository at: " + tmpRepoDir +
                                       ", to: " + primaryRepoDir, e);
        }
        checkState(!tmpRepoDir.exists(), "%s is not renamed.", tmpRepoDir);
        logger.debug("Migrating {} is done.", repoDir);
    }

    @VisibleForTesting
    void internalClose() {
        close(() -> new CentralDogmaException("should never reach here"));
    }

    /**
     * Waits until all pending operations are complete and closes this repository.
     *
     * @param failureCauseSupplier the {@link Supplier} that creates a new {@link CentralDogmaException}
     *                             which will be used to fail the operations issued after this method is called
     */
    void close(Supplier<CentralDogmaException> failureCauseSupplier) {
        requireNonNull(failureCauseSupplier, "failureCauseSupplier");
        if (closePending.compareAndSet(null, failureCauseSupplier)) {
            repositoryWorker.execute(() -> {
                rwLock.writeLock().lock();
                try {
                    closeInternalRepository(primaryRepo);
                    closeInternalRepository(secondaryRepo);
                    repoMetadata.close();
                } finally {
                    rwLock.writeLock().unlock();
                    commitWatchers.close(failureCauseSupplier);
                    closeFuture.complete(null);
                }
            });
        }

        closeFuture.join();
    }

    private static void closeInternalRepository(@Nullable InternalRepository internalRepo) {
        if (internalRepo == null) {
            return;
        }
        internalRepo.close();
    }

    @Override
    public Project parent() {
        return parent;
    }

    @Override
    public String name() {
        return originalRepoName;
    }

    @Override
    public long creationTimeMillis() {
        return creationTimeMillis;
    }

    @Override
    public Author author() {
        return author;
    }

    @Override
    public Revision normalizeNow(Revision revision) {
        return normalizeNow(revision, true);
    }

    private Revision normalizeNow(Revision revision, boolean checkFirstRevision) {
        return normalizeNow(revision, headRevision.major(), checkFirstRevision);
    }

    @Override
    public RevisionRange normalizeNow(Revision from, Revision to) {
        final int headMajor = headRevision.major();
        return new RevisionRange(normalizeNow(from, headMajor, true), normalizeNow(to, headMajor, true));
    }

    private Revision normalizeNow(Revision revision, int headMajor, boolean checkFirstRevision) {
        requireNonNull(revision, "revision");
        int major = revision.major();

        if (major >= 0) {
            if (major > headMajor) {
                throw new RevisionNotFoundException(
                        "revision: " + revision + " (expected: <= " + headMajor + ")");
            }
        } else {
            major = headMajor + major + 1;
            if (major <= 0) {
                throw new RevisionNotFoundException(
                        "revision: " + revision + " (expected: " + revision + " + " + headMajor + " + 1 > 0)");
            }
        }

        if (checkFirstRevision) {
            final int firstRevisionMajor = primaryRepo.firstRevision().major();
            if (major < firstRevisionMajor) {
                if (major == 1) {
                    // We silently update the major to the first revision when it's Revision.INIT.
                    major = firstRevisionMajor;
                } else {
                    throw new RevisionNotFoundException(
                            "revision: " + revision + " (expected: >= " + firstRevisionMajor + ")");
                }
            }
        }

        // Create a new instance only when necessary.
        if (revision.major() == major) {
            return revision;
        } else {
            return new Revision(major);
        }
    }

    @Override
    public CompletableFuture<Map<String, Entry<?>>> find(
            Revision revision, String pathPattern, Map<FindOption<?>, ?> options) {
        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(revision, "revision");
        requireNonNull(options, "options");
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "find", revision, pathPattern, options);
            readLock();
            try {
                final Revision normalizedRevision = normalizeNow(revision);
                if ("/".equals(pathPattern)) {
                    return ImmutableMap.of(pathPattern, Entry.ofDirectory(normalizedRevision, "/"));
                }
                return primaryRepo.find(normalizedRevision, pathPattern, options);
            } finally {
                readUnlock();
            }
        }, repositoryWorker);
    }

    /**
     * Get the diff between any two valid revisions.
     *
     * @param from revision from
     * @param to revision to
     * @param pathPattern target path pattern
     * @return the map of changes mapped by path
     * @throws StorageException if {@code from} or {@code to} does not exist.
     */
    @Override
    public CompletableFuture<Map<String, Change<?>>> diff(Revision from, Revision to, String pathPattern) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        requireNonNull(pathPattern, "pathPattern");
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "diff", from, to, pathPattern);
            readLock();
            try {
                final RevisionRange range = normalizeNow(from, to).toAscending();
                if (range.from().equals(range.to())) {
                    // Empty range.
                    return ImmutableMap.of();
                }
                return primaryRepo.diff(range, pathPattern);
            } finally {
                readUnlock();
            }
        }, repositoryWorker);
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> previewDiff(Revision baseRevision,
                                                                 Iterable<Change<?>> changes) {
        requireNonNull(baseRevision, "baseRevision");
        requireNonNull(changes, "changes");
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "previewDiff", baseRevision);
            readLock();
            try {
                final Revision normalizedRevision = normalizeNow(baseRevision);
                return primaryRepo.previewDiff(normalizedRevision, changes);
            } finally {
                readUnlock();
            }
        }, repositoryWorker);
    }

    @Override
    public CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis, Author author,
                                                  String summary, String detail, Markup markup,
                                                  Iterable<Change<?>> changes, boolean directExecution) {
        requireNonNull(baseRevision, "baseRevision");
        requireNonNull(author, "author");
        requireNonNull(summary, "summary");
        requireNonNull(detail, "detail");
        requireNonNull(markup, "markup");
        requireNonNull(changes, "changes");

        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "commit", baseRevision, author, summary);
            return blockingCommit(baseRevision, commitTimeMillis,
                                  author, summary, detail, markup, changes, directExecution);
        }, repositoryWorker);
    }

    private CommitResult blockingCommit(
            Revision baseRevision, long commitTimeMillis, Author author, String summary,
            String detail, Markup markup, Iterable<Change<?>> changes, boolean directExecution) {
        final RevisionAndEntries res;
        final Iterable<Change<?>> applyingChanges;
        writeLock();
        try {
            final Revision normalizedRevision = normalizeNow(baseRevision);
            final Revision headRevision = this.headRevision;
            if (headRevision.major() != normalizedRevision.major()) {
                throw new ChangeConflictException(
                        "invalid baseRevision: " + baseRevision + " (expected: " + headRevision +
                        " or equivalent)");
            }

            if (directExecution) {
                applyingChanges = primaryRepo.previewDiff(normalizedRevision, changes).values();
            } else {
                applyingChanges = changes;
            }
            res = primaryRepo.commit(headRevision, headRevision.forward(1), commitTimeMillis,
                                     author, summary, detail, markup, applyingChanges, false);
            this.headRevision = res.revision;
            final InternalRepository secondaryRepo = this.secondaryRepo;
            if (secondaryRepo != null) {
                assert headRevision.equals(secondaryRepo.headRevision());

                // Push the same commit to the secondary repo.
                secondaryRepo.commit(headRevision, headRevision.forward(1), commitTimeMillis,
                                     author, summary, detail, markup, applyingChanges, false);
            }
        } finally {
            writeUnLock();
        }

        // Note that the notification is made while no lock is held to avoid the risk of a dead lock.
        notifyWatchers(res.revision, res.diffEntries);
        return CommitResult.of(res.revision, applyingChanges);
    }

    private void notifyWatchers(Revision newRevision, List<DiffEntry> diffEntries) {
        for (DiffEntry entry : diffEntries) {
            switch (entry.getChangeType()) {
                case ADD:
                    commitWatchers.notify(newRevision, entry.getNewPath());
                    break;
                case MODIFY:
                case DELETE:
                    commitWatchers.notify(newRevision, entry.getOldPath());
                    break;
                default:
                    throw new Error();
            }
        }
    }

    @Override
    public CompletableFuture<List<Commit>> history(Revision from, Revision to, String pathPattern,
                                                   int maxCommits) {
        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        checkArgument(maxCommits > 0, "maxCommits: %s (expected: > 0)", maxCommits);
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "history", from, to, pathPattern, maxCommits);
            readLock();
            try {
                final RevisionRange range = normalizeNow(from, to);
                return primaryRepo.listCommits(pathPattern, Math.min(maxCommits, MAX_MAX_COMMITS), range);
            } finally {
                readUnlock();
            }
        }, repositoryWorker);
    }

    @Override
    public CompletableFuture<Revision> findLatestRevision(Revision lastKnownRevision, String pathPattern,
                                                          boolean errorOnEntryNotFound) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        requireNonNull(pathPattern, "pathPattern");
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "findLatestRevision", lastKnownRevision, pathPattern);
            return blockingFindLatestRevision(lastKnownRevision, pathPattern, errorOnEntryNotFound);
        }, repositoryWorker);
    }

    @Nullable
    private Revision blockingFindLatestRevision(Revision lastKnownRevision, String pathPattern,
                                                boolean errorOnEntryNotFound) {
        if (lastKnownRevision.major() == Revision.INIT.major()) {
            // Fast path: no need to compare because we are sure there is nothing at revision 1.
            final Revision headRevision = this.headRevision;
            if (headRevision.major() == 1) {
                if (errorOnEntryNotFound) {
                    throw new EntryNotFoundException(lastKnownRevision, pathPattern);
                }

                return null;
            }
            if ("/".equals(pathPattern)) {
                return headRevision;
            }
            readLock();
            try {
                final Map<String, Entry<?>> entries = primaryRepo.find(
                        headRevision, pathPattern, FindOptions.FIND_ONE_WITHOUT_CONTENT);
                if (!entries.isEmpty()) {
                    return headRevision;
                }
                if (errorOnEntryNotFound) {
                    throw new EntryNotFoundException(lastKnownRevision, pathPattern);
                }
                return null;
            } finally {
                readUnlock();
            }
        }

        // Slow path: compare the two trees.
        final List<DiffEntry> diffEntries;
        final RevisionRange range;
        readLock();
        try {
            range = new RevisionRange(normalizeNow(lastKnownRevision, false), headRevision);
            if (range.from().isLowerThan(primaryRepo.firstRevision())) {
                // We should return range.to() without comparing two tree. It's because:
                // - the entry might be changed between the lastKnownRevision(in the previous repository
                //   that is removed and superseded by the current primaryRepo)
                //   and first revision(in the current primaryRepo).
                // - but the previous commits before the first revision is packed and committed to the
                //   primaryRepo at once, we really don't know if there was a change.
                // - so it's safe to return the latest revision so that the client get notified when watching.
                return range.to();
            }
            if (range.from().equals(range.to())) {
                // Empty range.
                if (!errorOnEntryNotFound) {
                    return null;
                }
                // We have to check if we have the entry.
                final Map<String, Entry<?>> entries =
                        primaryRepo.find(range.to(), pathPattern, FindOptions.FIND_ONE_WITHOUT_CONTENT);
                if (!entries.isEmpty()) {
                    // We have the entry so just return null because there's no change.
                    return null;
                }
                throw new EntryNotFoundException(lastKnownRevision, pathPattern);
            }
            diffEntries = primaryRepo.diff(range, cache);
        } finally {
            readUnlock();
        }

        final PathPatternFilter filter = PathPatternFilter.of(pathPattern);
        // Return the latest revision if the changes between the two trees contain the file.
        for (DiffEntry e : diffEntries) {
            final String path;
            switch (e.getChangeType()) {
                case ADD:
                    path = e.getNewPath();
                    break;
                case MODIFY:
                case DELETE:
                    path = e.getOldPath();
                    break;
                default:
                    throw new Error();
            }

            if (filter.matches(path)) {
                return range.to();
            }
        }

        if (!errorOnEntryNotFound) {
            return null;
        }
        if (!primaryRepo.find(range.to(), pathPattern, FindOptions.FIND_ONE_WITHOUT_CONTENT).isEmpty()) {
            // We have to make sure that the entry does not exist because the size of diffEntries can be 0
            // when the contents of range.from() and range.to() are identical. (e.g. add, remove and add again)
            return null;
        }
        throw new EntryNotFoundException(lastKnownRevision, pathPattern);
    }

    @Override
    public CompletableFuture<Revision> watch(Revision lastKnownRevision, String pathPattern,
                                             boolean errorOnEntryNotFound) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        requireNonNull(pathPattern, "pathPattern");

        final ServiceRequestContext ctx = context();
        final CompletableFuture<Revision> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "watch", lastKnownRevision, pathPattern);
            readLock();
            try {
                final Revision normLastKnownRevision = normalizeNow(lastKnownRevision, false);
                // If lastKnownRevision is outdated already and the recent changes match,
                // there's no need to watch.
                final Revision latestRevision = blockingFindLatestRevision(normLastKnownRevision, pathPattern,
                                                                           errorOnEntryNotFound);
                if (latestRevision != null) {
                    future.complete(latestRevision);
                } else {
                    commitWatchers.add(normLastKnownRevision, pathPattern, future);
                }
            } finally {
                readUnlock();
            }
        }, repositoryWorker).exceptionally(cause -> {
            future.completeExceptionally(cause);
            return null;
        });

        return future;
    }

    private void readLock() {
        rwLock.readLock().lock();
        if (closePending.get() != null) {
            rwLock.readLock().unlock();
            throw closePending.get().get();
        }
    }

    private void readUnlock() {
        rwLock.readLock().unlock();
    }

    private void writeLock() {
        rwLock.writeLock().lock();
        if (closePending.get() != null) {
            writeUnLock();
            throw closePending.get().get();
        }
    }

    private void writeUnLock() {
        rwLock.writeLock().unlock();
    }

    private static Commit currentInitialCommit(InternalRepository primaryRepo) {
        final Revision firstRevision = primaryRepo.firstRevision();
        final RevisionRange range = new RevisionRange(firstRevision, firstRevision);
        return primaryRepo.listCommits(ALL_PATH, 1, range).get(0);
    }

    @Override
    public Revision shouldCreateRollingRepository(int minRetentionCommits, int minRetentionDays) {
        final InternalRepository repo = secondaryRepo != null ? secondaryRepo : primaryRepo;
        return shouldCreateRollingRepository(repo.headRevision(), minRetentionCommits, minRetentionDays);
    }

    @Nullable
    private Revision shouldCreateRollingRepository(Revision headRevision, int minRetentionCommits,
                                                   int minRetentionDays) {
        // TODO(minwoox): provide a way to set different minRetentionCommits and minRetentionDays
        //                in each repository.
        if ((minRetentionCommits == 0 && minRetentionDays == 0) ||
            (minRetentionCommits == Integer.MAX_VALUE && minRetentionDays == Integer.MAX_VALUE)) {
            // Not enabled.
            return null;
        }

        final InternalRepository repo = secondaryRepo != null ? secondaryRepo : primaryRepo;
        if (exceedsMinRetention(repo, headRevision, minRetentionCommits, minRetentionDays)) {
            return headRevision;
        }
        return null;
    }

    private static boolean exceedsMinRetention(InternalRepository repo, Revision headRevision,
                                               int minRetentionCommits, int minRetentionDays) {
        final Revision firstRevision = repo.firstRevision();
        if (minRetentionCommits != 0) {
            if (headRevision.major() - firstRevision.major() <= minRetentionCommits) {
                return false;
            }
        }

        if (minRetentionDays != 0) {
            final Instant secondCommitCreationTime = repo.secondCommitCreationTimeInstant();
            return secondCommitCreationTime != null &&
                   secondCommitCreationTime.isBefore(Instant.now().minus(minRetentionDays, ChronoUnit.DAYS));
        }
        return true;
    }

    @Override
    public void createRollingRepository(Revision rollingRepositoryInitialRevision,
                                        int minRetentionCommits, int minRetentionDays) {
        requireNonNull(rollingRepositoryInitialRevision, "rollingRepositoryInitialRevision");
        final Revision rollingRepositoryRevision = shouldCreateRollingRepository(rollingRepositoryInitialRevision,
                                                                minRetentionCommits, minRetentionDays);
        checkState(rollingRepositoryRevision == rollingRepositoryInitialRevision,
                   "shouldCreateRollingRepository() returns %s. (expected: %s)", rollingRepositoryRevision,
                   rollingRepositoryInitialRevision);

        if (secondaryRepo != null) {
            promoteSecondaryRepo();
        }
        createSecondaryRepo(rollingRepositoryInitialRevision);
    }

    private void promoteSecondaryRepo() {
        writeLock();
        try {
            checkState(primaryRepo.headRevision().equals(secondaryRepo.headRevision()), "");

            logger.info("Promoting the secondary repository in {}/{}.", parent.name(), originalRepoName);
            repoMetadata.setPrimaryRepoDir(secondaryRepo.jGitRepo().getDirectory());
            final InternalRepository primaryRepo = this.primaryRepo;
            this.primaryRepo = secondaryRepo;
            this.secondaryRepo = null;
            repositoryWorker.execute(() -> {
                closeInternalRepository(primaryRepo);
                final File repoDir = primaryRepo.repoDir();
                final Path path = repoDir.toPath();
                final Path newPath = path.resolveSibling(path.getFileName().toString() + SUFFIX_REMOVED);
                try {
                    Files.move(path, newPath);
                } catch (IOException e) {
                    logger.warn("Failed to mark the old primary repository: {} to {}", repoDir, newPath);
                }
            });
            logger.info("Promotion is done for {}/{}. {} is now the primary.",
                        parent.name(), originalRepoName, secondaryRepo.repoDir());
        } finally {
            writeUnLock();
        }
    }

    private void createSecondaryRepo(Revision rollingRepositoryInitialRevision) {
        InternalRepository secondaryRepo = null;
        try {
            logger.info("Creating the secondary repository in {}/{}. head revision: {}.",
                        parent.name(), originalRepoName, rollingRepositoryInitialRevision);
            final List<Change<?>> changes = changes(rollingRepositoryInitialRevision);
            final File secondaryRepoDir = repoMetadata.secondaryRepoDir();
            secondaryRepo = InternalRepository.of(parent, originalRepoName, secondaryRepoDir,
                                                  rollingRepositoryInitialRevision, creationTimeMillis,
                                                  author, changes);
            writeLock();
            try {
                final Revision headRevision = this.headRevision;
                if (!rollingRepositoryInitialRevision.equals(headRevision)) {
                    assert rollingRepositoryInitialRevision.major() < headRevision.major();
                    // There were commits after the createRollingRepositoryCommand is created
                    // so we should catch up.
                    final RevisionRange revisionRange = new RevisionRange(
                            rollingRepositoryInitialRevision.forward(1), headRevision);
                    final List<Commit> commits = primaryRepo.listCommits(ALL_PATH, MAX_MAX_COMMITS,
                                                                         revisionRange);
                    Revision fromRevision = rollingRepositoryInitialRevision;
                    for (Commit commit : commits) {
                        final Revision toRevision = commit.revision();
                        final Map<String, Change<?>> diffs = primaryRepo.diff(
                                new RevisionRange(fromRevision, toRevision), ALL_PATH);
                        secondaryRepo.commit(fromRevision, toRevision, commit.when(), commit.author(),
                                             commit.summary(), commit.detail(), commit.markup(), diffs.values(),
                                             false);
                        fromRevision = toRevision;
                    }
                }
                this.secondaryRepo = secondaryRepo;
            } finally {
                writeUnLock();
            }
            logger.info("The secondary repository {} is created in {}/{}. head revision: {}",
                        secondaryRepoDir.getName(), parent.name(), originalRepoName,
                        rollingRepositoryInitialRevision);
        } catch (Throwable t) {
            logger.warn("Failed to create the secondary repository", t);
            if (secondaryRepo != null) {
                closeInternalRepository(secondaryRepo);
                try {
                    deleteDirectory(secondaryRepo.repoDir());
                } catch (IOException e) {
                    logger.warn("Failed to delete the directory: {}", secondaryRepo.repoDir());
                }
            }
        }
    }

    private List<Change<?>> changes(Revision rollingRepositoryInitialRevision) {
        final Map<String, Entry<?>> entries = primaryRepo.find(
                rollingRepositoryInitialRevision, ALL_PATH, ImmutableMap.of());
        final Builder<Change<?>> builder = ImmutableList.builder();
        for (Entry<?> entry : entries.values()) {
            final EntryType type = entry.type();
            if (type == EntryType.DIRECTORY) {
                continue;
            }
            if (type == EntryType.JSON) {
                final JsonNode content = (JsonNode) entry.content();
                builder.add(Change.ofJsonUpsert(entry.path(), content));
            } else {
                assert type == EntryType.TEXT;
                final String content = (String) entry.content();
                builder.add(Change.ofTextUpsert(entry.path(), content));
            }
        }
        return builder.build();
    }

    private static boolean deleteDirectory(File dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            return walk.sorted(Comparator.reverseOrder())
                       .map(Path::toFile)
                       .map(File::delete)
                       // Return false if it fails to delete a file.
                       .reduce(true, (a, b) -> a && b);
        }
    }
}
