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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.FailFastUtil.context;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.FailFastUtil.failFastIfTimedOut;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_REPO_FORMAT_VERSION;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdOwnerMap;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.TreeRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.SystemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceMode;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.command.ContentTransformer;
import com.linecorp.centraldogma.server.internal.IsolatedSystemReader;
import com.linecorp.centraldogma.server.internal.JGitUtil;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.CacheableCall;
import com.linecorp.centraldogma.server.storage.repository.DiffResultType;
import com.linecorp.centraldogma.server.storage.repository.FindOption;
import com.linecorp.centraldogma.server.storage.repository.FindOptions;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryListener;

import io.netty.channel.EventLoop;

/**
 * A {@link Repository} based on Git.
 */
class GitRepository implements Repository {

    private static final Logger logger = LoggerFactory.getLogger(GitRepository.class);

    static final String R_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;

    private static final Pattern CR = Pattern.compile("\r", Pattern.LITERAL);

    private static final Field revWalkObjectsField;

    static {
        final String jgitPomProperties = "META-INF/maven/org.eclipse.jgit/org.eclipse.jgit/pom.properties";
        try (InputStream is = SystemReader.class.getClassLoader().getResourceAsStream(jgitPomProperties)) {
            final Properties props = new Properties();
            props.load(is);
            final Object jgitVersion = props.get("version");
            if (jgitVersion != null) {
                logger.info("Using JGit: {}", jgitVersion);
            }
        } catch (IOException e) {
            logger.debug("Failed to read JGit version", e);
        }

        IsolatedSystemReader.install();

        Field field = null;
        try {
            field = RevWalk.class.getDeclaredField("objects");
            if (field.getType() != ObjectIdOwnerMap.class) {
                throw new IllegalStateException(
                        RevWalk.class.getSimpleName() + ".objects is not an " +
                        ObjectIdOwnerMap.class.getSimpleName() + '.');
            }
            field.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    RevWalk.class.getSimpleName() + ".objects does not exist.");
        }

        revWalkObjectsField = field;
    }

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Project parent;
    private final Executor repositoryWorker;
    private final long creationTimeMillis;
    private final Author author;
    @VisibleForTesting
    @Nullable
    final RepositoryCache cache;
    private final String name;
    private final org.eclipse.jgit.lib.Repository jGitRepository;
    private final CommitIdDatabase commitIdDatabase;
    @VisibleForTesting
    final CommitWatchers commitWatchers = new CommitWatchers();
    private final AtomicReference<Supplier<CentralDogmaException>> closePending = new AtomicReference<>();
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private final List<RepositoryListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * The current head revision. Initialized by the constructor and updated by commit().
     */
    private volatile Revision headRevision;

    /**
     * Creates a new Git-backed repository.
     *
     * @param repoDir the location of this repository
     * @param repositoryWorker the {@link Executor} which will perform the blocking repository operations
     * @param creationTimeMillis the creation time
     * @param author the user who initiated the creation of this repository
     *
     * @throws StorageException if failed to create a new repository
     */
    @VisibleForTesting
    GitRepository(Project parent, File repoDir, Executor repositoryWorker,
                  long creationTimeMillis, Author author) {
        this(parent, repoDir, repositoryWorker, creationTimeMillis, author, null);
    }

    /**
     * Creates a new Git-backed repository.
     *
     * @param repoDir the location of this repository
     * @param repositoryWorker the {@link Executor} which will perform the blocking repository operations
     * @param creationTimeMillis the creation time
     * @param author the user who initiated the creation of this repository
     *
     * @throws StorageException if failed to create a new repository
     */
    GitRepository(Project parent, File repoDir, Executor repositoryWorker,
                  long creationTimeMillis, Author author, @Nullable RepositoryCache cache) {

        this.parent = requireNonNull(parent, "parent");
        name = requireNonNull(repoDir, "repoDir").getName();
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
        this.creationTimeMillis = creationTimeMillis;
        this.author = requireNonNull(author, "author");
        this.cache = cache;

        final RepositoryBuilder repositoryBuilder = new RepositoryBuilder().setGitDir(repoDir).setBare();
        boolean success = false;
        try {
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
            head.link(Constants.R_HEADS + Constants.MASTER);

            // Initialize the commit ID database.
            commitIdDatabase = new CommitIdDatabase(jGitRepository);

            new CommitExecutor(this, creationTimeMillis, author, "Create a new repository", "",
                               Markup.PLAINTEXT, true)
                    .executeInitialCommit();

            headRevision = Revision.INIT;
            success = true;
        } catch (IOException e) {
            throw new StorageException("failed to create a repository at: " + repoDir, e);
        } finally {
            if (!success) {
                internalClose();
                // Failed to create a repository. Remove any cruft so that it is not loaded on the next run.
                deleteCruft(repoDir);
            }
        }
    }

    /**
     * Opens an existing Git-backed repository.
     *
     * @param repoDir the location of this repository
     * @param repositoryWorker the {@link Executor} which will perform the blocking repository operations
     *
     * @throws StorageException if failed to open the repository at the specified location
     */
    GitRepository(Project parent, File repoDir, Executor repositoryWorker, @Nullable RepositoryCache cache) {
        this.parent = requireNonNull(parent, "parent");
        name = requireNonNull(repoDir, "repoDir").getName();
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
        this.cache = cache;

        final RepositoryBuilder repositoryBuilder = new RepositoryBuilder().setGitDir(repoDir).setBare();
        try {
            jGitRepository = repositoryBuilder.build();
            if (!exist(repoDir)) {
                throw new RepositoryNotFoundException(repoDir.toString());
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

        boolean success = false;
        try {
            headRevision = uncachedHeadRevision();
            commitIdDatabase = new CommitIdDatabase(jGitRepository);
            if (!headRevision.equals(commitIdDatabase.headRevision())) {
                commitIdDatabase.rebuild(jGitRepository);
                assert headRevision.equals(commitIdDatabase.headRevision());
            }
            final Commit initialCommit = blockingHistory(Revision.INIT, Revision.INIT, ALL_PATH, 1).get(0);
            creationTimeMillis = initialCommit.when();
            author = initialCommit.author();
            success = true;
        } finally {
            if (!success) {
                internalClose();
            }
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
                // MUST acquire gcLock first to prevent a dead lock
                rwLock.writeLock().lock();
                try {
                    if (commitIdDatabase != null) {
                        try {
                            commitIdDatabase.close();
                        } catch (Exception e) {
                            logger.warn("Failed to close a commitId database:", e);
                        }
                    }

                    if (jGitRepository != null) {
                        try {
                            jGitRepository.close();
                        } catch (Exception e) {
                            logger.warn("Failed to close a Git repository: {}",
                                        jGitRepository.getDirectory(), e);
                        }
                    }
                } finally {
                    try {
                        rwLock.writeLock().unlock();
                    } finally {
                        commitWatchers.close(failureCauseSupplier);
                        closeFuture.complete(null);
                    }
                }
            });
        }

        closeFuture.join();
    }

    void internalClose() {
        close(() -> new CentralDogmaException("should never reach here"));
    }

    CommitIdDatabase commitIdDatabase() {
        return commitIdDatabase;
    }

    @Override
    public org.eclipse.jgit.lib.Repository jGitRepository() {
        return jGitRepository;
    }

    @Override
    public Project parent() {
        return parent;
    }

    @Override
    public String name() {
        return name;
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
        return normalizeNow(revision, cachedHeadRevision().major());
    }

    private static Revision normalizeNow(Revision revision, int baseMajor) {
        requireNonNull(revision, "revision");

        int major = revision.major();

        if (major >= 0) {
            if (major > baseMajor) {
                throw new RevisionNotFoundException(revision);
            }
        } else {
            major = baseMajor + major + 1;
            if (major <= 0) {
                throw new RevisionNotFoundException(revision);
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
    public RevisionRange normalizeNow(Revision from, Revision to) {
        final int baseMajor = cachedHeadRevision().major();
        return new RevisionRange(normalizeNow(from, baseMajor), normalizeNow(to, baseMajor));
    }

    @Override
    public CompletableFuture<Map<String, Entry<?>>> find(
            Revision revision, String pathPattern, Map<FindOption<?>, ?> options) {
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "find", revision, pathPattern, options);
            return blockingFind(revision, pathPattern, options);
        }, repositoryWorker);
    }

    private Map<String, Entry<?>> blockingFind(
            Revision revision, String pathPattern, Map<FindOption<?>, ?> options) {

        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(revision, "revision");
        requireNonNull(options, "options");

        final Revision normRevision = normalizeNow(revision);
        final boolean fetchContent = FindOption.FETCH_CONTENT.get(options);
        final int maxEntries = FindOption.MAX_ENTRIES.get(options);

        readLock();
        try (ObjectReader reader = jGitRepository.newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = newRevWalk(reader)) {

            // Query on a non-exist revision will return empty result.
            final Revision headRevision = cachedHeadRevision();
            if (normRevision.compareTo(headRevision) > 0) {
                return Collections.emptyMap();
            }

            if ("/".equals(pathPattern)) {
                return Collections.singletonMap(pathPattern, Entry.ofDirectory(normRevision, "/"));
            }

            final Map<String, Entry<?>> result = new LinkedHashMap<>();
            final ObjectId commitId = commitIdDatabase.get(normRevision);
            final RevCommit revCommit = revWalk.parseCommit(commitId);
            final PathPatternFilter filter = PathPatternFilter.of(pathPattern);

            final RevTree revTree = revCommit.getTree();
            treeWalk.addTree(revTree.getId());
            while (treeWalk.next() && result.size() < maxEntries) {
                final boolean matches = filter.matches(treeWalk);
                final String path = '/' + treeWalk.getPathString();

                // Recurse into a directory if necessary.
                if (treeWalk.isSubtree()) {
                    if (matches) {
                        // Add the directory itself to the result set if its path matches the pattern.
                        result.put(path, Entry.ofDirectory(normRevision, path));
                    }

                    treeWalk.enterSubtree();
                    continue;
                }

                if (!matches) {
                    continue;
                }

                // Build an entry as requested.
                final Entry<?> entry;
                final EntryType entryType = EntryType.guessFromPath(path);
                if (fetchContent) {
                    final byte[] content = reader.open(treeWalk.getObjectId(0)).getBytes();
                    switch (entryType) {
                        case JSON:
                            final JsonNode jsonNode = Jackson.readTree(content);
                            entry = Entry.ofJson(normRevision, path, jsonNode);
                            break;
                        case TEXT:
                            final String strVal = sanitizeText(new String(content, UTF_8));
                            entry = Entry.ofText(normRevision, path, strVal);
                            break;
                        default:
                            throw new Error("unexpected entry type: " + entryType);
                    }
                } else {
                    switch (entryType) {
                        case JSON:
                            entry = Entry.ofJson(normRevision, path, Jackson.nullNode);
                            break;
                        case TEXT:
                            entry = Entry.ofText(normRevision, path, "");
                            break;
                        default:
                            throw new Error("unexpected entry type: " + entryType);
                    }
                }

                result.put(path, entry);
            }

            return Util.unsafeCast(result);
        } catch (CentralDogmaException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(
                    "failed to get data from '" + parent.name() + '/' + name + "' at " + pathPattern +
                    " for " + revision, e);
        } finally {
            readUnlock();
        }
    }

    @Override
    public CompletableFuture<List<Commit>> history(
            Revision from, Revision to, String pathPattern, int maxCommits) {

        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "history", from, to, pathPattern, maxCommits);
            return blockingHistory(from, to, pathPattern, maxCommits);
        }, repositoryWorker);
    }

    @VisibleForTesting
    List<Commit> blockingHistory(Revision from, Revision to, String pathPattern, int maxCommits) {
        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        if (maxCommits <= 0) {
            throw new IllegalArgumentException("maxCommits: " + maxCommits + " (expected: > 0)");
        }

        maxCommits = Math.min(maxCommits, MAX_MAX_COMMITS);

        final RevisionRange range = normalizeNow(from, to);
        final RevisionRange descendingRange = range.toDescending();

        // At this point, we are sure: from.major >= to.major
        readLock();
        final RepositoryCache cache =
                // Do not cache too old data.
                (descendingRange.from().major() < headRevision.major() - MAX_MAX_COMMITS * 3) ? null
                                                                                              : this.cache;
        try (ObjectReader objectReader = jGitRepository.newObjectReader();
             RevWalk revWalk = newRevWalk(new CachingTreeObjectReader(this, objectReader, cache))) {
            final ObjectIdOwnerMap<?> revWalkInternalMap =
                    (ObjectIdOwnerMap<?>) revWalkObjectsField.get(revWalk);

            final ObjectId fromCommitId = commitIdDatabase.get(descendingRange.from());
            final ObjectId toCommitId = commitIdDatabase.get(descendingRange.to());

            revWalk.markStart(revWalk.parseCommit(fromCommitId));
            revWalk.setRetainBody(false);

            // Instead of relying on RevWalk to filter the commits,
            // we let RevWalk yield all commits so we can:
            // - Have more control on when iteration should be stopped.
            //   (A single Iterator.next() doesn't take long.)
            // - Clean up the internal map as early as possible.
            final RevFilter filter = new TreeRevFilter(revWalk, AndTreeFilter.create(
                    TreeFilter.ANY_DIFF, PathPatternFilter.of(pathPattern)));

            // Search up to 1000 commits when maxCommits <= 100.
            // Search up to (maxCommits * 10) commits when 100 < maxCommits <= 1000.
            final int maxNumProcessedCommits = Math.max(maxCommits * 10, MAX_MAX_COMMITS);

            final List<Commit> commitList = new ArrayList<>();
            int numProcessedCommits = 0;
            for (RevCommit revCommit : revWalk) {
                numProcessedCommits++;

                if (filter.include(revWalk, revCommit)) {
                    revWalk.parseBody(revCommit);
                    commitList.add(toCommit(revCommit));
                    revCommit.disposeBody();
                }

                if (revCommit.getId().equals(toCommitId) ||
                    commitList.size() >= maxCommits ||
                    // Prevent from iterating for too long.
                    numProcessedCommits >= maxNumProcessedCommits) {
                    break;
                }

                // Clear the internal lookup table of RevWalk to reduce the memory usage.
                // This is safe because we have linear history and traverse in one direction.
                if (numProcessedCommits % 16 == 0) {
                    revWalkInternalMap.clear();
                }
            }

            // Include the initial empty commit only when the caller specified
            // the initial revision (1) in the range and the pathPattern contains '/**'.
            if (commitList.size() < maxCommits &&
                descendingRange.to().major() == 1 &&
                pathPattern.contains(ALL_PATH)) {
                try (RevWalk tmpRevWalk = newRevWalk()) {
                    final RevCommit lastRevCommit = tmpRevWalk.parseCommit(toCommitId);
                    commitList.add(toCommit(lastRevCommit));
                }
            }

            if (!descendingRange.equals(range)) { // from and to is swapped so reverse the list.
                Collections.reverse(commitList);
            }

            return commitList;
        } catch (CentralDogmaException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(
                    "failed to retrieve the history: " + parent.name() + '/' + name +
                    " (" + pathPattern + ", " + from + ".." + to + ')', e);
        } finally {
            readUnlock();
        }
    }

    private static Commit toCommit(RevCommit revCommit) {
        final Author author;
        final PersonIdent committerIdent = revCommit.getCommitterIdent();
        final long when;
        if (committerIdent == null) {
            author = Author.UNKNOWN;
            when = 0;
        } else {
            author = new Author(committerIdent.getName(), committerIdent.getEmailAddress());
            when = committerIdent.getWhen().getTime();
        }

        try {
            return CommitUtil.newCommit(author, when, revCommit.getFullMessage());
        } catch (Exception e) {
            throw new StorageException("failed to create a Commit", e);
        }
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> diff(Revision from, Revision to, String pathPattern,
                                                          DiffResultType diffResultType) {
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            requireNonNull(from, "from");
            requireNonNull(to, "to");
            requireNonNull(pathPattern, "pathPattern");

            failFastIfTimedOut(this, logger, ctx, "diff", from, to, pathPattern);

            final RevisionRange range = normalizeNow(from, to).toAscending();
            readLock();
            try (RevWalk rw = newRevWalk()) {
                final RevTree treeA = rw.parseTree(commitIdDatabase.get(range.from()));
                final RevTree treeB = rw.parseTree(commitIdDatabase.get(range.to()));

                // Compare the two Git trees.
                // Note that we do not cache here because CachingRepository caches the final result already.
                return toChangeMap(blockingCompareTreesUncached(
                        treeA, treeB, pathPatternFilterOrTreeFilter(pathPattern)), diffResultType);
            } catch (StorageException e) {
                throw e;
            } catch (Exception e) {
                throw new StorageException("failed to parse two trees: range=" + range, e);
            } finally {
                readUnlock();
            }
        }, repositoryWorker);
    }

    private static TreeFilter pathPatternFilterOrTreeFilter(@Nullable String pathPattern) {
        if (pathPattern == null) {
            return TreeFilter.ALL;
        }

        final PathPatternFilter pathPatternFilter = PathPatternFilter.of(pathPattern);
        return pathPatternFilter.matchesAll() ? TreeFilter.ALL : pathPatternFilter;
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> previewDiff(Revision baseRevision,
                                                                 Iterable<Change<?>> changes) {
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "previewDiff", baseRevision);
            return blockingPreviewDiff(baseRevision, new DefaultChangesApplier(changes));
        }, repositoryWorker);
    }

    Map<String, Change<?>> blockingPreviewDiff(Revision baseRevision, AbstractChangesApplier changesApplier) {
        baseRevision = normalizeNow(baseRevision);

        readLock();
        try (ObjectReader reader = jGitRepository.newObjectReader();
             RevWalk revWalk = newRevWalk(reader);
             DiffFormatter diffFormatter = new DiffFormatter(null)) {

            final ObjectId baseTreeId = toTree(revWalk, baseRevision);
            final DirCache dirCache = DirCache.newInCore();
            final int numEdits = changesApplier.apply(jGitRepository, baseRevision, baseTreeId, dirCache);
            if (numEdits == 0) {
                return Collections.emptyMap();
            }

            final CanonicalTreeParser p = new CanonicalTreeParser();
            p.reset(reader, baseTreeId);
            diffFormatter.setRepository(jGitRepository);
            final List<DiffEntry> result = diffFormatter.scan(p, new DirCacheIterator(dirCache));
            return toChangeMap(result, DiffResultType.NORMAL);
        } catch (IOException e) {
            throw new StorageException("failed to perform a dry-run diff", e);
        } finally {
            readUnlock();
        }
    }

    private Map<String, Change<?>> toChangeMap(List<DiffEntry> diffEntryList, DiffResultType diffResultType) {
        try (ObjectReader reader = jGitRepository.newObjectReader()) {
            final Map<String, Change<?>> changeMap = new LinkedHashMap<>();

            for (DiffEntry diffEntry : diffEntryList) {
                final String oldPath = '/' + diffEntry.getOldPath();
                final String newPath = '/' + diffEntry.getNewPath();

                switch (diffEntry.getChangeType()) {
                    case MODIFY:
                        final EntryType oldEntryType = EntryType.guessFromPath(oldPath);
                        switch (oldEntryType) {
                            case JSON:
                                if (!oldPath.equals(newPath)) {
                                    putChange(changeMap, oldPath, Change.ofRename(oldPath, newPath));
                                }

                                final JsonNode oldJsonNode =
                                        Jackson.readTree(
                                                reader.open(diffEntry.getOldId().toObjectId()).getBytes());
                                final JsonNode newJsonNode =
                                        Jackson.readTree(
                                                reader.open(diffEntry.getNewId().toObjectId()).getBytes());
                                final JsonPatch patch =
                                        JsonPatch.generate(oldJsonNode, newJsonNode, ReplaceMode.SAFE);

                                if (!patch.isEmpty()) {
                                    if (diffResultType == DiffResultType.PATCH_TO_UPSERT) {
                                        putChange(changeMap, newPath,
                                                  Change.ofJsonUpsert(newPath, newJsonNode));
                                    } else {
                                        putChange(changeMap, newPath,
                                                  Change.ofJsonPatch(newPath, Jackson.valueToTree(patch)));
                                    }
                                }
                                break;
                            case TEXT:
                                final String oldText = sanitizeText(new String(
                                        reader.open(diffEntry.getOldId().toObjectId()).getBytes(), UTF_8));

                                final String newText = sanitizeText(new String(
                                        reader.open(diffEntry.getNewId().toObjectId()).getBytes(), UTF_8));

                                if (!oldPath.equals(newPath)) {
                                    putChange(changeMap, oldPath, Change.ofRename(oldPath, newPath));
                                }

                                if (!oldText.equals(newText)) {
                                    if (diffResultType == DiffResultType.PATCH_TO_UPSERT) {
                                        putChange(changeMap, newPath, Change.ofTextUpsert(newPath, newText));
                                    } else {
                                        putChange(changeMap, newPath,
                                                  Change.ofTextPatch(newPath, oldText, newText));
                                    }
                                }
                                break;
                            default:
                                throw new Error("unexpected old entry type: " + oldEntryType);
                        }
                        break;
                    case ADD:
                        final EntryType newEntryType = EntryType.guessFromPath(newPath);
                        switch (newEntryType) {
                            case JSON: {
                                final JsonNode jsonNode = Jackson.readTree(
                                        reader.open(diffEntry.getNewId().toObjectId()).getBytes());

                                putChange(changeMap, newPath, Change.ofJsonUpsert(newPath, jsonNode));
                                break;
                            }
                            case TEXT: {
                                final String text = sanitizeText(new String(
                                        reader.open(diffEntry.getNewId().toObjectId()).getBytes(), UTF_8));

                                putChange(changeMap, newPath, Change.ofTextUpsert(newPath, text));
                                break;
                            }
                            default:
                                throw new Error("unexpected new entry type: " + newEntryType);
                        }
                        break;
                    case DELETE:
                        putChange(changeMap, oldPath, Change.ofRemoval(oldPath));
                        break;
                    default:
                        throw new Error();
                }
            }
            return changeMap;
        } catch (Exception e) {
            throw new StorageException("failed to convert list of DiffEntry to Changes map", e);
        }
    }

    private static void putChange(Map<String, Change<?>> changeMap, String path, Change<?> change) {
        final Change<?> oldChange = changeMap.put(path, change);
        assert oldChange == null;
    }

    @Override
    public CompletableFuture<CommitResult> commit(
            Revision baseRevision, long commitTimeMillis, Author author, String summary,
            String detail, Markup markup, Iterable<Change<?>> changes, boolean directExecution) {
        requireNonNull(baseRevision, "baseRevision");
        requireNonNull(author, "author");
        requireNonNull(summary, "summary");
        requireNonNull(detail, "detail");
        requireNonNull(markup, "markup");
        requireNonNull(changes, "changes");
        final CommitExecutor commitExecutor =
                new CommitExecutor(this, commitTimeMillis, author, summary, detail, markup, false);
        return commit(baseRevision, commitExecutor, normBaseRevision -> {
            if (!directExecution) {
                return changes;
            }
            return blockingPreviewDiff(normBaseRevision, new DefaultChangesApplier(changes)).values();
        });
    }

    @Override
    public CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis, Author author,
                                                  String summary, String detail, Markup markup,
                                                  ContentTransformer<?> transformer) {
        requireNonNull(baseRevision, "baseRevision");
        requireNonNull(author, "author");
        requireNonNull(summary, "summary");
        requireNonNull(detail, "detail");
        requireNonNull(markup, "markup");
        requireNonNull(transformer, "transformer");
        final CommitExecutor commitExecutor =
                new CommitExecutor(this, commitTimeMillis, author, summary, detail, markup, false);
        return commit(baseRevision, commitExecutor,
                      normBaseRevision -> blockingPreviewDiff(
                              normBaseRevision, new TransformingChangesApplier(transformer)).values());
    }

    private CompletableFuture<CommitResult> commit(
            Revision baseRevision,
            CommitExecutor commitExecutor,
            Function<Revision, Iterable<Change<?>>> applyingChangesProvider) {
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "commit", baseRevision,
                               commitExecutor.author(), commitExecutor.summary());
            return commitExecutor.execute(baseRevision, applyingChangesProvider);
        }, repositoryWorker);
    }

    /**
     * Removes {@code \r} and appends {@code \n} on the last line if it does not end with {@code \n}.
     */
    static String sanitizeText(String text) {
        if (text.indexOf('\r') >= 0) {
            text = CR.matcher(text).replaceAll("");
        }
        if (!text.isEmpty() && !text.endsWith("\n")) {
            text += "\n";
        }
        return text;
    }

    static void doRefUpdate(org.eclipse.jgit.lib.Repository jGitRepository, RevWalk revWalk,
                            String ref, ObjectId commitId) throws IOException {

        if (ref.startsWith(Constants.R_TAGS)) {
            final Ref oldRef = jGitRepository.exactRef(ref);
            if (oldRef != null) {
                throw new StorageException("tag ref exists already: " + ref);
            }
        }

        final RefUpdate refUpdate = jGitRepository.updateRef(ref);
        refUpdate.setNewObjectId(commitId);

        final Result res = refUpdate.update(revWalk);
        switch (res) {
            case NEW:
            case FAST_FORWARD:
                // Expected
                break;
            default:
                throw new StorageException("unexpected refUpdate state: " + res);
        }
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
        final RevisionRange range = normalizeNow(lastKnownRevision, Revision.HEAD);
        if (range.from().equals(range.to())) {
            // Empty range.
            if (!errorOnEntryNotFound) {
                return null;
            }
            // We have to check if we have the entry.
            final Map<String, Entry<?>> entries =
                    blockingFind(range.to(), pathPattern, FindOptions.FIND_ONE_WITHOUT_CONTENT);
            if (!entries.isEmpty()) {
                // We have the entry so just return null because there's no change.
                return null;
            }
            throw new EntryNotFoundException(lastKnownRevision, pathPattern);
        }

        if (range.from().major() == 1) {
            // Fast path: no need to compare because we are sure there is nothing at revision 1.
            final Map<String, Entry<?>> entries =
                    blockingFind(range.to(), pathPattern, FindOptions.FIND_ONE_WITHOUT_CONTENT);
            if (entries.isEmpty()) {
                if (!errorOnEntryNotFound) {
                    return null;
                }
                throw new EntryNotFoundException(lastKnownRevision, pathPattern);
            } else {
                return range.to();
            }
        }

        // Slow path: compare the two trees.
        final PathPatternFilter filter = PathPatternFilter.of(pathPattern);
        // Convert the revisions to Git trees.
        final List<DiffEntry> diffEntries;
        readLock();
        try (RevWalk revWalk = newRevWalk()) {
            final RevTree treeA = toTree(revWalk, range.from());
            final RevTree treeB = toTree(revWalk, range.to());
            diffEntries = blockingCompareTrees(treeA, treeB);
        } finally {
            readUnlock();
        }

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
        if (!blockingFind(range.to(), pathPattern, FindOptions.FIND_ONE_WITHOUT_CONTENT).isEmpty()) {
            // We have to make sure that the entry does not exist because the size of diffEntries can be 0
            // when the contents of range.from() and range.to() are identical. (e.g. add, remove and add again)
            return null;
        }
        throw new EntryNotFoundException(lastKnownRevision, pathPattern);
    }

    /**
     * Compares the two Git trees (with caching).
     */
    private List<DiffEntry> blockingCompareTrees(RevTree treeA, RevTree treeB) {
        if (cache == null) {
            return blockingCompareTreesUncached(treeA, treeB, TreeFilter.ALL);
        }

        final CacheableCompareTreesCall key = new CacheableCompareTreesCall(this, treeA, treeB);
        return cache.get(key).join();
    }

    List<DiffEntry> blockingCompareTreesUncached(@Nullable RevTree treeA,
                                                 @Nullable RevTree treeB,
                                                 TreeFilter filter) {
        readLock();
        try (DiffFormatter diffFormatter = new DiffFormatter(null)) {
            diffFormatter.setRepository(jGitRepository);
            diffFormatter.setPathFilter(filter);
            return ImmutableList.copyOf(diffFormatter.scan(treeA, treeB));
        } catch (IOException e) {
            throw new StorageException("failed to compare two trees: " + treeA + " vs. " + treeB, e);
        } finally {
            readUnlock();
        }
    }

    @Override
    public CompletableFuture<Revision> watch(Revision lastKnownRevision, String pathPattern,
                                             boolean errorOnEntryNotFound) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        requireNonNull(pathPattern, "pathPattern");

        final ServiceRequestContext ctx = context();
        final Revision normLastKnownRevision = normalizeNow(lastKnownRevision);
        final CompletableFuture<Revision> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "watch", lastKnownRevision, pathPattern);
            readLock();
            try {
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

    @Override
    public <T> CompletableFuture<T> execute(CacheableCall<T> cacheableCall) {
        // This is executed only when the CachingRepository is not enabled.
        requireNonNull(cacheableCall, "cacheableCall");
        final ServiceRequestContext ctx = context();

        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "execute", cacheableCall);
            return cacheableCall.execute();
        }, repositoryWorker).thenCompose(Function.identity());
    }

    @Override
    public void addListener(RepositoryListener listener) {
        listeners.add(listener);
        final EventLoop executor = CommonPools.workerGroup().next();
        recursiveWatch(listener, Revision.INIT, executor);
    }

    private void recursiveWatch(RepositoryListener listener, Revision lastKnownRevision, EventLoop executor) {
        if (shouldStopListening(listener)) {
            return;
        }

        final String pathPattern = listener.pathPattern();
        watch(lastKnownRevision, pathPattern).handle((newRevision, cause) -> {
            if (cause != null) {
                cause = Exceptions.peel(cause);
                if (cause instanceof ShuttingDownException) {
                    return null;
                }

                logger.warn("Failed to watch {} file in {}/{}. Try watching after 5 seconds.",
                            pathPattern, parent.name(), name, cause);
                executor.schedule(() -> recursiveWatch(listener, lastKnownRevision, executor),
                                  5, TimeUnit.SECONDS);
                return null;
            }

            find(newRevision, pathPattern).handle((entries, cause0) -> {
                if (cause0 != null) {
                    cause0 = Exceptions.peel(cause0);
                    if (cause0 instanceof ShuttingDownException) {
                        return null;
                    }

                    logger.warn("Unexpected exception while retrieving {} in {}/{}. " +
                                "Try watching after 5 seconds.", pathPattern, parent.name(), name, cause0);
                    executor.schedule(() -> recursiveWatch(listener, newRevision, executor),
                                      5, TimeUnit.SECONDS);
                    return null;
                }

                try {
                    listener.onUpdate(entries);
                } catch (Exception ex) {
                    logger.warn("Unexpected exception while invoking {}.onUpdate(). listener: {}",
                                RepositoryListener.class.getSimpleName(), listener);
                }
                recursiveWatch(listener, newRevision, executor);
                return null;
            });
            return null;
        });
    }

    private boolean shouldStopListening(RepositoryListener listener) {
        return !listeners.contains(listener) || closePending.get() != null;
    }

    @Override
    public void removeListener(RepositoryListener listener) {
        listeners.remove(listener);
    }

    void notifyWatchers(Revision newRevision, List<DiffEntry> diffEntries) {
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

    Revision cachedHeadRevision() {
        return headRevision;
    }

    void setHeadRevision(Revision headRevision) {
        this.headRevision = headRevision;
    }

    /**
     * Returns the current revision.
     */
    private Revision uncachedHeadRevision() {
        try (RevWalk revWalk = newRevWalk()) {
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

        throw new StorageException("failed to determine the HEAD: " + parent.name() + '/' + name);
    }

    private RevTree toTree(RevWalk revWalk, Revision revision) {
        return toTree(commitIdDatabase, revWalk, revision);
    }

    static RevTree toTree(CommitIdDatabase commitIdDatabase, RevWalk revWalk, Revision revision) {
        final ObjectId commitId = commitIdDatabase.get(revision);
        try {
            return revWalk.parseCommit(commitId).getTree();
        } catch (IOException e) {
            throw new StorageException("failed to parse a commit: " + commitId, e);
        }
    }

    private RevWalk newRevWalk() {
        final RevWalk revWalk = new RevWalk(jGitRepository);
        configureRevWalk(revWalk);
        return revWalk;
    }

    static RevWalk newRevWalk(ObjectReader reader) {
        final RevWalk revWalk = new RevWalk(reader);
        configureRevWalk(revWalk);
        return revWalk;
    }

    private static void configureRevWalk(RevWalk revWalk) {
        // Disable rewriteParents because otherwise `RevWalk` will load every commit into memory.
        revWalk.setRewriteParents(false);
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

    void writeLock() {
        rwLock.writeLock().lock();
        if (closePending.get() != null) {
            writeUnLock();
            throw closePending.get().get();
        }
    }

    void writeUnLock() {
        rwLock.writeLock().unlock();
    }

    /**
     * Clones this repository into a new one.
     */
    public void cloneTo(File newRepoDir) {
        cloneTo(newRepoDir, (current, total) -> { /* no-op */ });
    }

    /**
     * Clones this repository into a new one.
     */
    public void cloneTo(File newRepoDir, BiConsumer<Integer, Integer> progressListener) {
        requireNonNull(newRepoDir, "newRepoDir");
        requireNonNull(progressListener, "progressListener");

        final Revision endRevision = normalizeNow(Revision.HEAD);
        final GitRepository newRepo = new GitRepository(parent, newRepoDir, repositoryWorker,
                                                        creationTimeMillis(), author(), cache);

        progressListener.accept(1, endRevision.major());
        boolean success = false;
        try {
            // Replay all commits.
            Revision previousNonEmptyRevision = null;
            for (int i = 2; i <= endRevision.major();) {
                // Fetch up to 16 commits at once.
                final int batch = 16;
                final List<Commit> commits = blockingHistory(
                        new Revision(i), new Revision(Math.min(endRevision.major(), i + batch - 1)),
                        ALL_PATH, batch);
                checkState(!commits.isEmpty(), "empty commits");

                if (previousNonEmptyRevision == null) {
                    previousNonEmptyRevision = commits.get(0).revision().backward(1);
                }
                for (Commit c : commits) {
                    final Revision revision = c.revision();
                    checkState(revision.major() == i,
                               "mismatching revision: %s (expected: %s)", revision.major(), i);

                    final Revision baseRevision = revision.backward(1);
                    final Collection<Change<?>> changes =
                            diff(previousNonEmptyRevision, revision, ALL_PATH).join().values();

                    try {
                        new CommitExecutor(newRepo, c.when(), c.author(), c.summary(),
                                           c.detail(), c.markup(), false)
                                .execute(baseRevision, normBaseRevision -> blockingPreviewDiff(
                                        normBaseRevision, new DefaultChangesApplier(changes)).values());
                        previousNonEmptyRevision = revision;
                    } catch (RedundantChangeException e) {
                        // NB: We allow an empty commit here because an old version of Central Dogma had a bug
                        //     which allowed the creation of an empty commit.
                        new CommitExecutor(newRepo, c.when(), c.author(), c.summary(),
                                                  c.detail(), c.markup(), true)
                                .execute(baseRevision, normBaseRevision -> blockingPreviewDiff(
                                        normBaseRevision, new DefaultChangesApplier(changes)).values());
                    }

                    progressListener.accept(i, endRevision.major());
                    i++;
                }
            }

            success = true;
        } finally {
            newRepo.internalClose();
            if (!success) {
                deleteCruft(newRepoDir);
            }
        }
    }

    private static void deleteCruft(File repoDir) {
        try {
            Util.deleteFileTree(repoDir);
        } catch (IOException e) {
            logger.error("Failed to delete a half-created repository at: {}", repoDir, e);
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("dir", jGitRepository.getDirectory())
                          .toString();
    }
}
