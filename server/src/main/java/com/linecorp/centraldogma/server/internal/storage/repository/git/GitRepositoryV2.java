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

import static com.linecorp.centraldogma.server.internal.storage.repository.git.FailFastUtil.context;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.FailFastUtil.failFastIfTimedOut;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.applyChanges;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.closeJGitRepo;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.deleteDirectory;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.doRefUpdate;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.getCommits;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.isEmpty;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.newRevWalk;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.toTree;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_COMMIT_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_DIFF_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_ALGORITHM;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_FILEMODE;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_GPGSIGN;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_HIDEDOTFILES;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_RENAMES;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_REPO_FORMAT_VERSION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_SYMLINKS;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CoreConfig.HideDotFiles;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
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
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceMode;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.FindOption;
import com.linecorp.centraldogma.server.storage.repository.FindOptions;

class GitRepositoryV2 implements com.linecorp.centraldogma.server.storage.repository.Repository {

    private static final Logger logger = LoggerFactory.getLogger(GitRepositoryV2.class);

    private static final String PRIMARY_REPOSITORY_SUFFIX = "_primary";
    private static final String FOLLOWER_REPOSITORY_SUFFIX = "_secondary";

    static final String R_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;

    private static final byte[] EMPTY_BYTE = new byte[0];
    private static final Pattern CR = Pattern.compile("\r", Pattern.LITERAL);

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
    private final File repositoryDir;
    private final String name;
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

    // Guarded by the write lock.
    private boolean isCreatingSecondaryRepo;
    private final List<LaggedCommit> laggedCommitsForSecondary = new ArrayList<>();

    /**
     * The current head revision. Initialized by the constructor and updated by commit().
     */
    private volatile Revision headRevision;

    GitRepositoryV2(Project parent, File repositoryDir, Executor repositoryWorker,
                    long creationTimeMillis, Author author, @Nullable RepositoryCache cache) {

        this.parent = requireNonNull(parent, "parent");
        this.repositoryDir = requireNonNull(repositoryDir, "repositoryDir");
        name = repositoryDir.getName();
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
            primaryRepo = createInternalRepo(parent, name, primaryRepoDir, Revision.INIT,
                                             creationTimeMillis, author, ImmutableList.of());
        } catch (Throwable t) {
            repoMetadata.close();
            throw t;
        }
        headRevision = Revision.INIT;
        this.creationTimeMillis = creationTimeMillis;
        this.author = author;
    }

    @VisibleForTesting
    static InternalRepository createInternalRepo(Project parent, String originalRepoName, File repoDir,
                                                 Revision nextRevision, long commitTimeMillis, Author author,
                                                 Iterable<Change<?>> changes) {
        boolean success = false;
        InternalRepository internalRepo = null;
        try {
            createEmptyJGitRepo(repoDir);

            // Re-open the repository with the updated settings and format version.
            final Repository jGitRepo = new RepositoryBuilder().setGitDir(repoDir)
                                                               .build();
            internalRepo = new InternalRepository(parent, originalRepoName, repoDir,
                                                  jGitRepo, new CommitIdDatabase(jGitRepo));

            // Initialize the master branch.
            final RefUpdate head = jGitRepo.updateRef(Constants.HEAD);
            head.disableRefLog();
            head.link(R_HEADS_MASTER);

            // Insert the initial commit into the master branch.
            commit0(internalRepo, null, nextRevision, commitTimeMillis, author,
                    "Create a new repository", "", Markup.PLAINTEXT, changes, true);
            success = true;
            return internalRepo;
        } catch (IOException e) {
            throw new StorageException("failed to create a repository at: " + repoDir, e);
        } finally {
            if (!success) {
                closeInternalRepository(internalRepo);
                // Failed to create a repository. Remove any cruft so that it is not loaded on the next run.
                deleteCruft(repoDir);
            }
        }
    }

    private static void createEmptyJGitRepo(File repositoryDir) throws IOException {
        try (org.eclipse.jgit.lib.Repository initRepository = buildjGitRepo(repositoryDir)) {
            initRepository.create(true);

            final StoredConfig config = initRepository.getConfig();
            // Update the repository settings to upgrade to format version 1 and reftree.
            config.setInt(CONFIG_CORE_SECTION, null, CONFIG_KEY_REPO_FORMAT_VERSION, 1);

            // Disable hidden files, symlinks and file modes we do not use.
            config.setEnum(CONFIG_CORE_SECTION, null, CONFIG_KEY_HIDEDOTFILES, HideDotFiles.FALSE);
            config.setBoolean(CONFIG_CORE_SECTION, null, CONFIG_KEY_SYMLINKS, false);
            config.setBoolean(CONFIG_CORE_SECTION, null, CONFIG_KEY_FILEMODE, false);

            // Disable GPG signing.
            config.setBoolean(CONFIG_COMMIT_SECTION, null, CONFIG_KEY_GPGSIGN, false);

            // Set the diff algorithm.
            config.setString(CONFIG_DIFF_SECTION, null, CONFIG_KEY_ALGORITHM, "histogram");

            // Disable rename detection which we do not use.
            config.setBoolean(CONFIG_DIFF_SECTION, null, CONFIG_KEY_RENAMES, false);

            config.save();
        }
    }

    private static Repository buildjGitRepo(File repositoryDir) {
        try {
            return new RepositoryBuilder().setGitDir(repositoryDir).setBare().build();
        } catch (IOException e) {
            throw new StorageException("failed to create a repository at: " + repositoryDir, e);
        }
    }

    private GitRepositoryV2(Project parent, File repoDir, Executor repositoryWorker,
                            @Nullable RepositoryCache cache) {
        this.parent = requireNonNull(parent, "parent");
        this.repositoryDir = requireNonNull(repoDir, "repoDir");
        name = repoDir.getName();
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
        this.cache = cache;

        RepositoryMetadataDatabase repoMetadata;
        try {
            repoMetadata = new RepositoryMetadataDatabase(repoDir, false);
        } catch (Throwable t) {
            // The metadata doesn't exist so check if the repository exists in the form of the old version.
            final Repository oldRepo = buildjGitRepo(repoDir);
            final boolean oldRepoExist = exist(oldRepo);
            closeJGitRepo(oldRepo);
            if (!oldRepoExist) {
                throw new RepositoryNotFoundException(repoDir.toString());
            }
            final File primaryRepoDir = RepositoryMetadataDatabase.initialPrimaryRepoDir(repoDir);
            final File tmpRepoDir = new File(repoDir.getParentFile(), UUID.randomUUID().toString());
            logger.debug("Migrating {} to {} using temp repository: {}", repoDir, primaryRepoDir, tmpRepoDir);
            if (!repoDir.renameTo(tmpRepoDir)) {
                throw new StorageException("failed to migrate a repository at: " + repoDir +
                                           ", to the tmp dir: " + tmpRepoDir);
            }
            primaryRepoDir.mkdirs();
            if (!tmpRepoDir.renameTo(primaryRepoDir)) {
                // Rename it back.
                tmpRepoDir.renameTo(repoDir);
                throw new StorageException("failed to migrate a repository at: " + tmpRepoDir +
                                           ", to: " + primaryRepoDir);
            }
            assert !tmpRepoDir.exists();
            logger.debug("Migrating {} is done.", repoDir);
            repoMetadata = new RepositoryMetadataDatabase(repoDir, true);
        }

        this.repoMetadata = repoMetadata;
        try {
            final InternalRepository primaryRepo =
                    openInternalRepository(repoMetadata.primaryRepoDir(), true);
            assert primaryRepo != null;
            this.primaryRepo = primaryRepo;
            final Commit initialCommit = currentInitialCommit(primaryRepo);
            creationTimeMillis = initialCommit.when();
            author = initialCommit.author();
            secondaryRepo = openInternalRepository(repoMetadata.secondaryRepoDir(), false);
        } catch (Throwable t) {
            repoMetadata.close();
            closeInternalRepository(primaryRepo);
            closeInternalRepository(secondaryRepo);
            throw t;
        }
    }

    @Nullable
    private InternalRepository openInternalRepository(File repoDir, boolean primary) {
        final Repository jGitRepo = buildjGitRepo(repoDir);
        CommitIdDatabase commitIdDatabase = null;
        try {
            if (!exist(jGitRepo)) {
                if (primary) {
                    throw new RepositoryNotFoundException(repoDir.toString());
                } else {
                    return null;
                }
            }
            checkGitRepositoryFormat(jGitRepo);
            final Revision headRevision = uncachedHeadRevision(jGitRepo);
            commitIdDatabase = new CommitIdDatabase(jGitRepo);
            if (!headRevision.equals(commitIdDatabase.headRevision())) {
                commitIdDatabase.rebuild(jGitRepo);
                assert headRevision.equals(commitIdDatabase.headRevision());
            }
            if (primary) {
                this.headRevision = headRevision;
            }
            return new InternalRepository(parent, name, repoDir, jGitRepo, commitIdDatabase);
        } catch (Throwable t) {
            closeJGitRepo(jGitRepo);
            if (commitIdDatabase != null) {
                commitIdDatabase.close();
            }
            throw t;
        }
    }

    private static void checkGitRepositoryFormat(Repository repository) {
        final int formatVersion = repository.getConfig().getInt(
                CONFIG_CORE_SECTION, null, CONFIG_KEY_REPO_FORMAT_VERSION, 0);
        if (formatVersion != 1) {
            throw new StorageException("unsupported repository format version: " + formatVersion);
        }
    }

    private static boolean exist(Repository repository) {
        if (repository.getConfig() instanceof FileBasedConfig) {
            return ((FileBasedConfig) repository.getConfig()).getFile().exists();
        }
        return repository.getDirectory().exists();
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

    private static void deleteCruft(File repoDir) {
        try {
            Util.deleteFileTree(repoDir);
        } catch (IOException e) {
            logger.error("Failed to delete a half-created repository at: {}", repoDir, e);
        }
    }

    /**
     * Returns the current revision.
     */
    private Revision uncachedHeadRevision(Repository jGitRepo) {
        try (RevWalk revWalk = newRevWalk(jGitRepo)) {
            final ObjectId headRevisionId = jGitRepo.resolve(R_HEADS_MASTER);
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
        return normalizeNow(revision, headRevision.major());
    }

    private Revision normalizeNow(Revision revision, int headMajor) {
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

        final Revision firstRevision = primaryRepo.commitIdDatabase().firstRevision();
        assert firstRevision != null;
        final int firstMajor = firstRevision.major();
        if (major < firstMajor) {
            major = firstMajor;
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
        final int headMajor = headRevision.major();
        return new RevisionRange(normalizeNow(from, headMajor), normalizeNow(to, headMajor));
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
        final InternalRepository primaryRepo = this.primaryRepo;
        try (ObjectReader reader = primaryRepo.jGitRepo().newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = newRevWalk(reader)) {

            // Query on a non-exist revision will return empty result.
            final Revision headRevision = this.headRevision;
            if (normRevision.compareTo(headRevision) > 0) {
                return Collections.emptyMap();
            }

            if ("/".equals(pathPattern)) {
                return Collections.singletonMap(pathPattern, Entry.ofDirectory(normRevision, "/"));
            }

            final Map<String, Entry<?>> result = new LinkedHashMap<>();
            final ObjectId commitId = primaryRepo.commitIdDatabase().get(normRevision);
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
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            requireNonNull(from, "from");
            requireNonNull(to, "to");
            requireNonNull(pathPattern, "pathPattern");

            failFastIfTimedOut(this, logger, ctx, "diff", from, to, pathPattern);

            final RevisionRange range = normalizeNow(from, to).toAscending();
            readLock();
            final InternalRepository primaryRepo = this.primaryRepo;
            final Repository jGitRepo = primaryRepo.jGitRepo();
            try (RevWalk rw = newRevWalk(jGitRepo)) {
                final CommitIdDatabase commitIdDatabase = primaryRepo.commitIdDatabase();
                final RevTree treeA = rw.parseTree(commitIdDatabase.get(range.from()));
                final RevTree treeB = rw.parseTree(commitIdDatabase.get(range.to()));

                // Compare the two Git trees.
                // Note that we do not cache here because CachingRepository caches the final result already.
                return toChangeMap(jGitRepo,
                                   blockingCompareTreesUncached(jGitRepo, treeA, treeB,
                                                                pathPatternFilterOrTreeFilter(pathPattern)));
            } catch (StorageException e) {
                throw e;
            } catch (Exception e) {
                throw new StorageException("failed to parse two trees: range=" + range, e);
            } finally {
                readUnlock();
            }
        }, repositoryWorker);
    }

    private List<DiffEntry> blockingCompareTreesUncached(Repository jGitRepo,
                                                         @Nullable RevTree treeA, @Nullable RevTree treeB,
                                                         TreeFilter filter) {
        readLock();
        try (DiffFormatter diffFormatter = new DiffFormatter(null)) {
            diffFormatter.setRepository(jGitRepo);
            diffFormatter.setPathFilter(filter);
            return ImmutableList.copyOf(diffFormatter.scan(treeA, treeB));
        } catch (IOException e) {
            throw new StorageException("failed to compare two trees: " + treeA + " vs. " + treeB, e);
        } finally {
            readUnlock();
        }
    }

    private static TreeFilter pathPatternFilterOrTreeFilter(@Nullable String pathPattern) {
        if (pathPattern == null) {
            return TreeFilter.ALL;
        }

        final PathPatternFilter pathPatternFilter = PathPatternFilter.of(pathPattern);
        return pathPatternFilter.matchesAll() ? TreeFilter.ALL : pathPatternFilter;
    }

    private Map<String, Change<?>> toChangeMap(Repository jGitRepo, List<DiffEntry> diffEntryList) {
        try (ObjectReader reader = jGitRepo.newObjectReader()) {
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
                                    putChange(changeMap, newPath,
                                              Change.ofJsonPatch(newPath, Jackson.valueToTree(patch)));
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
                                    putChange(changeMap, newPath,
                                              Change.ofTextPatch(newPath, oldText, newText));
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

    /**
     * Removes {@code \r} and appends {@code \n} on the last line if it does not end with {@code \n}.
     */
    private static String sanitizeText(String text) {
        if (text.indexOf('\r') >= 0) {
            text = CR.matcher(text).replaceAll("");
        }
        if (!text.isEmpty() && !text.endsWith("\n")) {
            text += "\n";
        }
        return text;
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> previewDiff(Revision baseRevision,
                                                                 Iterable<Change<?>> changes) {
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "previewDiff", baseRevision);
            return blockingPreviewDiff(baseRevision, changes);
        }, repositoryWorker);
    }

    private Map<String, Change<?>> blockingPreviewDiff(Revision baseRevision, Iterable<Change<?>> changes) {
        requireNonNull(baseRevision, "baseRevision");
        requireNonNull(changes, "changes");
        baseRevision = normalizeNow(baseRevision);

        readLock();
        final InternalRepository primaryRepo = this.primaryRepo;
        final Repository jGitRepo = primaryRepo.jGitRepo();
        try (ObjectReader reader = jGitRepo.newObjectReader();
             RevWalk revWalk = newRevWalk(reader);
             DiffFormatter diffFormatter = new DiffFormatter(null)) {

            final ObjectId baseTreeId = toTree(primaryRepo.commitIdDatabase(), revWalk, baseRevision);
            final DirCache dirCache = DirCache.newInCore();
            final int numEdits = applyChanges(jGitRepo, baseRevision, baseTreeId, dirCache, changes);
            if (numEdits == 0) {
                return Collections.emptyMap();
            }

            final CanonicalTreeParser p = new CanonicalTreeParser();
            p.reset(reader, baseTreeId);
            diffFormatter.setRepository(jGitRepo);
            final List<DiffEntry> result = diffFormatter.scan(p, new DirCacheIterator(dirCache));
            return toChangeMap(jGitRepo, result);
        } catch (IOException e) {
            throw new StorageException("failed to perform a dry-run diff", e);
        } finally {
            readUnlock();
        }
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
                                  author, summary, detail, markup, changes, false, directExecution);
        }, repositoryWorker);
    }

    private CommitResult blockingCommit(
            Revision baseRevision, long commitTimeMillis, Author author, String summary,
            String detail, Markup markup, Iterable<Change<?>> changes, boolean allowEmptyCommit,
            boolean directExecution) {

        requireNonNull(baseRevision, "baseRevision");

        final RevisionAndEntries res;
        final Iterable<Change<?>> applyingChanges;
        writeLock();
        try {
            final Revision normBaseRevision = normalizeNow(baseRevision);
            final Revision headRevision = this.headRevision;
            if (headRevision.major() != normBaseRevision.major()) {
                throw new ChangeConflictException(
                        "invalid baseRevision: " + baseRevision + " (expected: " + headRevision +
                        " or equivalent)");
            }

            if (directExecution) {
                applyingChanges = blockingPreviewDiff(normBaseRevision, changes).values();
            } else {
                applyingChanges = changes;
            }
            res = commit0(primaryRepo, headRevision, headRevision.forward(1), commitTimeMillis,
                          author, summary, detail, markup, applyingChanges, allowEmptyCommit);
            this.headRevision = res.revision;
            final InternalRepository secondaryRepo = this.secondaryRepo;
            if (secondaryRepo != null) {
                // Push the same commit to the secondary repo.
                commit0(secondaryRepo, headRevision, headRevision.forward(1), commitTimeMillis,
                        author, summary, detail, markup, applyingChanges, allowEmptyCommit);
            } else if (isCreatingSecondaryRepo) {
                laggedCommitsForSecondary.add(new LaggedCommit(
                        headRevision, commitTimeMillis, author, summary, detail, markup, applyingChanges,
                        allowEmptyCommit));
            }
        } finally {
            writeUnLock();
        }

        // Note that the notification is made while no lock is held to avoid the risk of a dead lock.
        notifyWatchers(res.revision, res.diffEntries);
        return CommitResult.of(res.revision, applyingChanges);
    }

    private static RevisionAndEntries commit0(InternalRepository repo, @Nullable Revision prevRevision,
                                              Revision nextRevision, long commitTimeMillis, Author author,
                                              String summary, String detail, Markup markup,
                                              Iterable<Change<?>> changes, boolean allowEmpty) {

        requireNonNull(author, "author");
        requireNonNull(summary, "summary");
        requireNonNull(changes, "changes");
        requireNonNull(detail, "detail");
        requireNonNull(markup, "markup");

        assert prevRevision == null || prevRevision.major() > 0;
        assert nextRevision.major() > 0;

        final Repository jGitRepo = repo.jGitRepo();
        final CommitIdDatabase commitIdDatabase = repo.commitIdDatabase();
        try (ObjectInserter inserter = jGitRepo.newObjectInserter();
             ObjectReader reader = jGitRepo.newObjectReader();
             RevWalk revWalk = newRevWalk(reader)) {

            final ObjectId prevTreeId;
            if (prevRevision != null) {
                prevTreeId = toTree(commitIdDatabase, revWalk, prevRevision);
            } else {
                prevTreeId = null;
            }

            // The staging area that keeps the entries of the new tree.
            // It starts with the entries of the tree at the prevRevision (or with no entries if the
            // prevRevision is the initial commit), and then this method will apply the requested changes
            // to build the new tree.
            final DirCache dirCache = DirCache.newInCore();

            // Apply the changes and retrieve the list of the affected files.
            final int numEdits = applyChanges(jGitRepo, prevRevision, prevTreeId, dirCache, changes);

            // Reject empty commit if necessary.
            final List<DiffEntry> diffEntries;
            boolean isEmpty = numEdits == 0;
            if (isEmpty || prevTreeId == null) {
                // We do not need the diffEntries when creating a new repository which means prevTreeId is null.
                diffEntries = ImmutableList.of();
            } else {
                // Even if there are edits, the resulting tree might be identical with the previous tree.
                final CanonicalTreeParser p = new CanonicalTreeParser();
                p.reset(reader, prevTreeId);
                final DiffFormatter diffFormatter = new DiffFormatter(null);
                diffFormatter.setRepository(jGitRepo);
                diffEntries = diffFormatter.scan(p, new DirCacheIterator(dirCache));
                isEmpty = diffEntries.isEmpty();
            }

            if (!allowEmpty && isEmpty) {
                throw new RedundantChangeException(
                        "changes did not change anything in " +
                        repo.project().name() + '/' + repo.originalRepoName() +
                        " at revision " + (prevRevision != null ? prevRevision.major() : 0) + ": " + changes);
            }

            // flush the current index to repository and get the result tree object id.
            final ObjectId nextTreeId = dirCache.writeTree(inserter);

            // build a commit object
            final PersonIdent personIdent = new PersonIdent(author.name(), author.email(),
                                                            commitTimeMillis / 1000L * 1000L, 0);

            final CommitBuilder commitBuilder = new CommitBuilder();

            commitBuilder.setAuthor(personIdent);
            commitBuilder.setCommitter(personIdent);
            commitBuilder.setTreeId(nextTreeId);
            commitBuilder.setEncoding(UTF_8);

            // Write summary, detail and revision to commit's message as JSON format.
            commitBuilder.setMessage(CommitUtil.toJsonString(summary, detail, markup, nextRevision));

            // if the head commit exists, use it as the parent commit.
            if (prevRevision != null) {
                commitBuilder.setParentId(commitIdDatabase.get(prevRevision));
            }

            final ObjectId nextCommitId = inserter.insert(commitBuilder);
            inserter.flush();

            // tagging the revision object, for history lookup purpose.
            commitIdDatabase.put(nextRevision, nextCommitId);
            doRefUpdate(jGitRepo, revWalk, R_HEADS_MASTER, nextCommitId);

            return new RevisionAndEntries(nextRevision, diffEntries);
        } catch (CentralDogmaException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to push at '" +
                                       repo.project().name() + '/' + repo.originalRepoName() + '\'', e);
        }
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
        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "history", from, to, pathPattern, maxCommits);
            return blockingHistory(from, to, pathPattern, maxCommits);
        }, repositoryWorker);
    }

    private List<Commit> blockingHistory(Revision from, Revision to, String pathPattern, int maxCommits) {
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
        final InternalRepository primaryRepo = this.primaryRepo;
        try {
            return getCommits(primaryRepo, pathPattern, maxCommits, range, descendingRange);
        } finally {
            readUnlock();
        }
    }

    @Override
    public CompletableFuture<Revision> findLatestRevision(Revision lastKnownRevision, String pathPattern) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        requireNonNull(pathPattern, "pathPattern");

        final ServiceRequestContext ctx = context();
        return CompletableFuture.supplyAsync(() -> {
            failFastIfTimedOut(this, logger, ctx, "findLatestRevision", lastKnownRevision, pathPattern);
            return blockingFindLatestRevision(lastKnownRevision, pathPattern);
        }, repositoryWorker);
    }

    @Nullable
    private Revision blockingFindLatestRevision(Revision lastKnownRevision, String pathPattern) {
        final RevisionRange range = normalizeNow(lastKnownRevision, Revision.HEAD);
        if (range.from().equals(range.to())) {
            // Empty range.
            return null;
        }

        if (range.from().major() == 1) {
            // Fast path: no need to compare because we are sure there is nothing at revision 1.
            final Map<String, Entry<?>> entries =
                    blockingFind(range.to(), pathPattern, FindOptions.FIND_ONE_WITHOUT_CONTENT);
            return !entries.isEmpty() ? range.to() : null;
        }

        // Slow path: compare the two trees.
        final PathPatternFilter filter = PathPatternFilter.of(pathPattern);
        // Convert the revisions to Git trees.
        final List<DiffEntry> diffEntries;
        readLock();
        final InternalRepository primaryRepo = this.primaryRepo;
        try (RevWalk revWalk = newRevWalk(primaryRepo.jGitRepo())) {
            final CommitIdDatabase commitIdDatabase = primaryRepo.commitIdDatabase();
            final RevTree treeA = toTree(commitIdDatabase, revWalk, range.from());
            final RevTree treeB = toTree(commitIdDatabase, revWalk, range.to());
            diffEntries = blockingCompareTrees(primaryRepo.jGitRepo(), treeA, treeB);
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

        return null;
    }

    /**
     * Compares the two Git trees (with caching).
     */
    private List<DiffEntry> blockingCompareTrees(Repository jGitRepo, RevTree treeA, RevTree treeB) {
        if (cache == null) {
            return blockingCompareTreesUncached(jGitRepo, treeA, treeB, TreeFilter.ALL);
        }

        final CacheableCompareTreesCall key = new CacheableCompareTreesCall(this, treeA, treeB);
        CompletableFuture<List<DiffEntry>> existingFuture = cache.getIfPresent(key);
        if (existingFuture != null) {
            final List<DiffEntry> existingDiffEntries = existingFuture.getNow(null);
            if (existingDiffEntries != null) {
                // Cached already.
                return existingDiffEntries;
            }
        }

        // Not cached yet. Acquire a lock so that we do not compare the same tree pairs simultaneously.
        final List<DiffEntry> newDiffEntries;
        final Lock lock = key.coarseGrainedLock();
        lock.lock();
        try {
            existingFuture = cache.getIfPresent(key);
            if (existingFuture != null) {
                final List<DiffEntry> existingDiffEntries = existingFuture.getNow(null);
                if (existingDiffEntries != null) {
                    // Other thread already put the entries to the cache before we acquire the lock.
                    return existingDiffEntries;
                }
            }

            newDiffEntries = blockingCompareTreesUncached(jGitRepo, treeA, treeB, TreeFilter.ALL);
            cache.put(key, newDiffEntries);
        } finally {
            lock.unlock();
        }

        logger.debug("Cache miss: {}", key);
        return newDiffEntries;
    }

    @Override
    public CompletableFuture<Revision> watch(Revision lastKnownRevision, String pathPattern) {
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
                final Revision latestRevision = blockingFindLatestRevision(normLastKnownRevision, pathPattern);
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

    private Commit currentInitialCommit(InternalRepository primaryRepo) {
        final Revision firstRevision = primaryRepo.commitIdDatabase().firstRevision();
        // This method is called when opening an existing repository so firstRevision is not null.
        assert firstRevision != null;
        final RevisionRange range = new RevisionRange(firstRevision, firstRevision);
        return getCommits(primaryRepo, ALL_PATH, 1, range, range).get(0);
    }

    private static List<Change<?>> toChanges(Map<String, Entry<?>> entries) {
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

    /**
     * This repository removes the old commits by creating the secondary repository that commits are pushed
     * together with the primary repository and removing the primary repository if the secondary repository
     * contains at least the number of {@code minRetentionCommits} and contains the recent
     * {@code minRetentionDays} commits.
     */
    @Override
    public void removeOldCommits(int minRetentionCommits, int minRetentionDays) {
        // TODO(minwoox): provide a way to set different minRetentionCommits and minRetentionDays
        //                in each repository.
        if (minRetentionCommits == 0 && minRetentionDays == 0) {
            // Not enabled.
            return;
        }

        final InternalRepository secondaryRepo = this.secondaryRepo;
        if (secondaryRepo != null) {
            if (exceedsMinRetention(secondaryRepo, minRetentionCommits, minRetentionDays)) {
                promoteSecondaryRepo();
            }
        } else if (exceedsMinRetention(primaryRepo, minRetentionCommits, minRetentionDays)) {
            createSecondaryRepo();
        }
    }

    private boolean exceedsMinRetention(InternalRepository repo,
                                        int minRetentionCommits, int minRetentionDays) {
        final CommitIdDatabase commitIdDatabase = repo.commitIdDatabase();
        final Revision headRevision = commitIdDatabase.headRevision();
        final Revision firstRevision = commitIdDatabase.firstRevision();
        if (headRevision == null || firstRevision == null) {
            return false;
        }
        if (minRetentionCommits != 0) {
            if (headRevision.major() - firstRevision.major() <= minRetentionCommits) {
                return false;
            }
        }

        if (minRetentionDays != 0) {
            final Instant creationTime = repo.secondCommitCreationTimeInstant();
            return creationTime != null &&
                   Instant.now().minus(Duration.ofDays(minRetentionDays)).isBefore(creationTime);
        }
        return true;
    }

    private void promoteSecondaryRepo() {
        final InternalRepository secondaryRepo = this.secondaryRepo;
        if (secondaryRepo != null) {
            writeLock();
            try {
                logger.info("Promoting the secondary repository in {}/{}.", parent.name(), name);
                repoMetadata.setPrimaryRepoDir(secondaryRepo.jGitRepo().getDirectory());
                final InternalRepository primaryRepo = this.primaryRepo;
                this.primaryRepo = secondaryRepo;
                this.secondaryRepo = null;
                repositoryWorker.execute(() -> {
                    closeInternalRepository(primaryRepo);
                    final File repoDir = primaryRepo.repoDir();
                    try {
                        if (!deleteDirectory(repoDir)) {
                            logger.warn("Failed to delete the old primary repository: {}", repoDir);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to delete the old primary repository: {}", repoDir);
                    }
                });
                logger.info("Promotion is done for {}/{}. {} is now the primary.",
                            parent.name(), name, secondaryRepo.repoDir());
            } finally {
                writeUnLock();
            }
        }

        createSecondaryRepo();
    }

    private void createSecondaryRepo() {
        try {
            writeLock();
            final Revision headRevision = this.headRevision;
            isCreatingSecondaryRepo = true;
            writeUnLock();

            logger.info("Creating the secondary repository in {}/{} with the head revision: {}.",
                        parent.name(), name, headRevision);
            final Map<String, Entry<?>> entries = find(headRevision, ALL_PATH, ImmutableMap.of()).join();
            final List<Change<?>> changes = toChanges(entries);
            final File secondaryRepoDir = repoMetadata.secondaryRepoDir();
            final InternalRepository secondaryRepo =
                    createInternalRepo(parent, name, secondaryRepoDir, headRevision,
                                       creationTimeMillis, author, changes);
            writeLock();
            try {
                if (laggedCommitsForSecondary.isEmpty()) {
                    // There were no commits after creating the secondary repo.
                    assert headRevision == this.headRevision;
                    this.secondaryRepo = secondaryRepo;
                } else {
                    // We should catch up.
                    for (LaggedCommit c : laggedCommitsForSecondary) {
                        commit0(secondaryRepo, c.revision, c.revision.forward(1), c.commitTimeMillis, c.author,
                                c.summary, c.detail, c.markup, c.changes, c.allowEmpty);
                    }
                    laggedCommitsForSecondary.clear();
                    isCreatingSecondaryRepo = false;
                    this.secondaryRepo = secondaryRepo;
                }
            } finally {
                writeUnLock();
            }
            logger.info("The secondary repository {} is created in {}/{}.",
                        secondaryRepoDir.getName(), parent.name(), name, headRevision);
        } catch (Throwable t) {
            logger.warn("Failed to create the secondary repository", t);
        }
    }

    private static final class RevisionAndEntries {
        final Revision revision;
        final List<DiffEntry> diffEntries;

        RevisionAndEntries(Revision revision, List<DiffEntry> diffEntries) {
            this.revision = revision;
            this.diffEntries = diffEntries;
        }
    }

    private static class LaggedCommit {

        private final Revision revision;
        private final long commitTimeMillis;
        private final Author author;
        private final String summary;
        private final String detail;
        private final Markup markup;
        private final Iterable<Change<?>> changes;
        private final boolean allowEmpty;

        LaggedCommit(Revision revision, long commitTimeMillis, Author author, String summary,
                     String detail, Markup markup, Iterable<Change<?>> changes,
                     boolean allowEmpty) {
            this.revision = revision;
            this.commitTimeMillis = commitTimeMillis;
            this.author = author;
            this.summary = summary;
            this.detail = detail;
            this.markup = markup;
            this.changes = changes;
            this.allowEmpty = allowEmpty;
        }
    }
}
