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

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEditor.DeletePath;
import org.eclipse.jgit.dircache.DirCacheEditor.DeleteTree;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefRename;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.FindOption;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatchException;
import com.linecorp.centraldogma.server.internal.storage.StorageException;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.ChangeConflictException;
import com.linecorp.centraldogma.server.internal.storage.repository.RedundantChangeException;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;
import com.linecorp.centraldogma.server.internal.storage.repository.RevisionNotFoundException;

import difflib.DiffUtils;
import difflib.Patch;
import difflib.PatchFailedException;

/**
 * A {@link Repository} based on Git.
 */
class GitRepository implements Repository {

    private static final Logger logger = LoggerFactory.getLogger(GitRepository.class);

    private static final byte[] EMPTY_BYTE = new byte[0];

    private static final String RUNSPACES = "runspaces/";
    private static final String RUNSPACES_REMOVED = "runspaces.removed/";

    private static final String R_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;
    private static final String R_HEADS_RUNSPACES = Constants.R_HEADS + RUNSPACES;
    private static final String R_HEADS_RUNSPACES_REMOVED = Constants.R_HEADS + RUNSPACES_REMOVED;

    private final Lock writeLock = new ReentrantLock();
    private final Project parent;
    private final Executor repositoryWorker;
    private final String name;
    private final org.eclipse.jgit.lib.Repository jGitRepository;
    private final CommitWatchers commitWatchers = new CommitWatchers();

    /**
     * The current head revision. Initialized by the constructor and updated by commit().
     */
    private volatile Revision headRevision;

    /**
     * Creates a new Git-backed repository.
     *
     * @param repoDir the location of this repository
     * @param repositoryWorker the {@link Executor} which will perform the blocking repository operations
     * @param author the user who initiated the creation of this repository
     *
     * @throws StorageException if failed to create a new repository
     */
    GitRepository(Project parent, File repoDir, Executor repositoryWorker, Author author) {
        this.parent = requireNonNull(parent, "parent");
        name = requireNonNull(repoDir, "repoDir").getName();
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");

        requireNonNull(author, "author");

        RepositoryBuilder repositoryBuilder = new RepositoryBuilder().setGitDir(repoDir).setBare();
        boolean success = false;
        try {
            jGitRepository = repositoryBuilder.build();
            if (exist(repoDir)) {
                throw new StorageException(
                        "failed to create a repository at: " + repoDir + " (exists already)");
            }

            jGitRepository.create(true);

            // Insert the initial commit.
            commit0(null, Revision.INIT, author, "Create a new repository", "", Markup.PLAINTEXT,
                    Collections.emptyList(), true);

            headRevision = Revision.INIT;
            success = true;
        } catch (IOException e) {
            throw new StorageException("failed to create a repository at: " + repoDir, e);
        } finally {
            if (!success) {
                // Failed to create a repository. Remove any cruft so that it is not loaded on the next run.
                try {
                    deltree(repoDir);
                } catch (IOError e) {
                    logger.warn("Failed to delete a half-created repository at: {}", repoDir, e);
                }
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
    GitRepository(Project parent, File repoDir, Executor repositoryWorker) {
        this.parent = requireNonNull(parent, "parent");
        name = requireNonNull(repoDir, "repoDir").getName();
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");

        RepositoryBuilder repositoryBuilder = new RepositoryBuilder().setGitDir(repoDir).setBare();
        try {
            jGitRepository = repositoryBuilder.build();
            if (!exist(repoDir)) {
                throw new StorageException("failed to open a repository at: " + repoDir + " (does not exist)");
            }
        } catch (IOException e) {
            throw new StorageException("failed to open a repository at: " + repoDir, e);
        }

        headRevision = uncachedMainLaneHeadRevision();
    }

    private static boolean exist(File repoDir) {
        try {
            RepositoryBuilder repositoryBuilder = new RepositoryBuilder().setGitDir(repoDir);
            org.eclipse.jgit.lib.Repository repository = repositoryBuilder.build();
            if (repository.getConfig() instanceof FileBasedConfig) {
                return ((FileBasedConfig)repository.getConfig()).getFile().exists();
            }
            return repository.getDirectory().exists();
        } catch (IOException e) {
            throw new StorageException("failed to check if repository exists at " + repoDir, e);
        }
    }

    /**
     * Deletes the specified {@code directory} recursively.
     */
    private static void deltree(File directory) {
        if (directory.exists()) {
            try {
                Files.walkFileTree(directory.toPath(), DeletingFileVisitor.INSTANCE);
            } catch (IOException e) {
                throw new IOError(e);
            }
        }
    }

    void close() {
        jGitRepository.close();
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
    public CompletableFuture<Revision> normalize(Revision revision) {
        // Don't bother caching runspace revisions; we do not use them at all.
        if (!revision.onMainLane()) {
            return CompletableFuture.supplyAsync(() -> blockingNormalize(revision), repositoryWorker);
        }

        final int maxMajor = headRevision.major();

        int major = revision.major();

        if (major >= 0) {
            if (major > maxMajor) {
                final CompletableFuture<Revision> future = new CompletableFuture<>();
                future.completeExceptionally(new RevisionNotFoundException(revision));
                return future;
            }
        } else {
            major = maxMajor + major + 1;
            if (major <= 0) {
                final CompletableFuture<Revision> future = new CompletableFuture<>();
                future.completeExceptionally(new RevisionNotFoundException(revision));
                return future;
            }
        }

        // Create a new instance only when necessary.
        if (revision.major() == major) {
            return CompletableFuture.completedFuture(revision);
        } else {
            return CompletableFuture.completedFuture(new Revision(major, 0));
        }
    }

    private Revision blockingNormalize(Revision revision) {
        requireNonNull(revision, "revision");

        final int baseMajor = cachedMainLaneHeadRevision().major();

        int major = revision.major();
        int minor = revision.minor();

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

        if (minor != 0) {
            final int baseMinor = runspaceHeadRevision(major).minor();
            if (minor > 0) {
                if (minor > baseMinor) {
                    throw new RevisionNotFoundException(revision);
                }
            } else {
                minor = baseMinor + minor + 1;
                if (minor <= 0) {
                    throw new RevisionNotFoundException(revision);
                }
            }
        }

        // Create a new instance only when necessary.
        if (revision.major() == major && revision.minor() == minor) {
            return revision;
        } else {
            return new Revision(major, minor);
        }
    }

    @Override
    public CompletableFuture<Map<String, Entry<?>>> find(
            Revision revision, String pathPattern, Map<FindOption<?>, ?> options) {

        return CompletableFuture.supplyAsync(() -> blockingFind(revision, pathPattern, options),
                                             repositoryWorker);
    }

    private Map<String, Entry<?>> blockingFind(
            Revision revision, String pathPattern, Map<FindOption<?>, ?> options) {

        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(revision, "revision");
        requireNonNull(options, "options");

        final Revision normRevision = blockingNormalize(revision);
        final boolean fetchContent = FindOption.FETCH_CONTENT.get(options);
        final int maxEntries = FindOption.MAX_ENTRIES.get(options);

        try (ObjectReader reader = jGitRepository.newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = new RevWalk(reader)) {

            // Query on a non-exist revision will return empty result.
            final Revision headRevision = cachedMainLaneHeadRevision();
            if (normRevision.compareTo(headRevision) > 0) {
                return Collections.emptyMap();
            }

            if ("/".equals(pathPattern)) {
                return Collections.singletonMap(pathPattern, Entry.rootDir());
            }

            final Map<String, Entry<?>> result = new LinkedHashMap<>();
            final ObjectId commitId = toCommitId(normRevision);
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
                        result.put(path, Entry.ofDirectory(path));
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
                        entry = Entry.ofJson(path, jsonNode);
                        break;
                    case TEXT:
                        final String strVal = new String(content, StandardCharsets.UTF_8);
                        entry = Entry.ofText(path, strVal);
                        break;
                    default:
                        throw new Error("unexpected entry type: " + entryType);
                    }
                } else {
                    switch (entryType) {
                    case JSON:
                        entry = Entry.ofJson(path, Jackson.nullNode);
                        break;
                    case TEXT:
                        entry = Entry.ofText(path, "");
                        break;
                    default:
                        throw new Error("unexpected entry type: " + entryType);
                    }
                }

                result.put(path, entry);
            }

            return Util.unsafeCast(result);
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(
                    "failed to get data from " + jGitRepository + " at " + pathPattern + " for " + revision, e);
        }
    }

    @Override
    public CompletableFuture<List<Commit>> history(
            Revision from, Revision to, String pathPattern, int maxCommits) {

        return CompletableFuture.supplyAsync(
                () -> blockingHistory(from, to, pathPattern, maxCommits), repositoryWorker);
    }

    private List<Commit> blockingHistory(Revision from, Revision to, String pathPattern, int maxCommits) {
        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        if (maxCommits <= 0) {
            throw new IllegalArgumentException("maxCommits: " + maxCommits + " (expected: > 0)");
        }

        final Revision origFrom = from;
        final Revision origTo = to;

        from = blockingNormalize(from);
        to = blockingNormalize(to);

        final boolean ascending = from.compareTo(to) < 0;
        final ObjectId fromCommitId;
        final ObjectId toCommitId;

        if (ascending) {
            // Swap 'from' and 'to'.
            Revision temp = to;
            to = from;
            from = temp;
        }

        fromCommitId = toCommitId(from);
        toCommitId = toCommitId(to);

        // At this point, we are sure: from.major >= to.major
        final boolean validCommitRange;
        if (from.minor() != 0) {
            if (to.minor() != 0) {
                // Both revisions are runspace revisions, and thus must belong to the same runspace.
                //
                //     GOOD:         BAD:
                //
                //     |   from      | from
                //     |   /         |/
                //     |  to         *  to
                //     | /           | /
                //     |/            |/
                //     *             *
                //
                validCommitRange = from.major() == to.major();
            } else {
                validCommitRange = true;
            }
        } else {
            // If 'from' is a main lane revision, 'to' must also be a mainline revision
            // because otherwise we will have to climb back up the runspace branch.
            //
            //   GOOD:     BAD:
            //
            //     |        |
            //   from     from  /
            //     |        |  to
            //     |        | /
            //     |        |/
            //    to        *
            //     |        |
            //
            validCommitRange = to.onMainLane();
        }

        if (!validCommitRange) {
            throw new IllegalArgumentException("invalid commit range: " + origFrom + " .. " + origTo);
        }

        try (RevWalk revWalk = new RevWalk(jGitRepository)) {
            // Walk through the commit tree to get the corresponding commit information by given filters
            revWalk.setTreeFilter(AndTreeFilter.create(TreeFilter.ANY_DIFF, PathPatternFilter.of(pathPattern)));

            revWalk.markStart(revWalk.parseCommit(fromCommitId));
            final RevCommit toCommit = revWalk.parseCommit(toCommitId);
            if (toCommit.getParentCount() != 0) {
                revWalk.markUninteresting(toCommit.getParent(0));
            } else {
                // The initial commit.
                revWalk.markUninteresting(toCommit);
            }

            final List<Commit> revisionList = new ArrayList<>();
            boolean needsLastCommit = true;
            for (RevCommit revCommit : revWalk) {
                final Commit c = toCommit(revCommit);
                if (c != null) {
                    revisionList.add(c);
                } else {
                    // Probably garbage (e.g. deleted runspace)
                    continue;
                }

                if (revCommit.getId().equals(toCommitId) || revisionList.size() == maxCommits) {
                    // Visited the last commit or can't retrieve beyond maxCommits
                    needsLastCommit = false;
                    break;
                }
            }

            // Handle the case where the last commit was not visited by the RevWalk,
            // which can happen when the commit is empty.  In our repository, an empty commit can only be made
            // when a new repository is created or a new runspace is created.
            if (needsLastCommit) {
                try (RevWalk tmpRevWalk = new RevWalk(jGitRepository)) {
                    final RevCommit lastRevCommit = tmpRevWalk.parseCommit(toCommitId);
                    final Revision lastCommitRevision =
                            CommitUtil.extractRevision(lastRevCommit.getFullMessage());
                    if (lastCommitRevision.major() == 1 || lastCommitRevision.minor() == 1) {
                        revisionList.add(toCommit(lastRevCommit));
                    }
                }
            }

            if (ascending) {
                Collections.reverse(revisionList);
            }

            return revisionList;
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(
                    "failed to retrieve the history: " + jGitRepository +
                    " (" + pathPattern + ", " + origFrom + ".." + origTo + ')', e);
        }
    }

    private static Commit toCommit(RevCommit revCommit) {
        final Author author;
        final PersonIdent committerIdent = revCommit.getCommitterIdent();
        if (committerIdent == null) {
            author = Author.UNKNOWN;
        } else {
            author = new Author(committerIdent.getName(), committerIdent.getEmailAddress());
        }
        long when = committerIdent.getWhen().getTime();

        try {
            return CommitUtil.newCommit(author, when, revCommit.getFullMessage());
        } catch (Exception e) {
            throw new StorageException("failed to create a Commit", e);
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
        return CompletableFuture.supplyAsync(() -> blockingDiff(from, to, pathPattern), repositoryWorker);
    }

    private Map<String, Change<?>> blockingDiff(Revision from, Revision to, String pathPattern) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        requireNonNull(pathPattern, "pathPattern");

        return toChangeMap(compareTrees(toCommitId(blockingNormalize(from)), toCommitId(blockingNormalize(to)),
                                        PathPatternFilter.of(pathPattern)));
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> previewDiff(Revision baseRevision,
                                                                 Iterable<Change<?>> changes) {
        return CompletableFuture.supplyAsync(
                () -> blockingPreviewDiff(baseRevision, changes), repositoryWorker);
    }

    private Map<String, Change<?>> blockingPreviewDiff(Revision baseRevision, Iterable<Change<?>> changes) {
        requireNonNull(baseRevision, "baseRevision");
        requireNonNull(changes, "changes");
        baseRevision = blockingNormalize(baseRevision);

        try (ObjectReader reader = jGitRepository.newObjectReader();
             RevWalk revWalk = new RevWalk(reader);
             DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE)) {

            final ObjectId baseTreeId = toTreeId(revWalk, baseRevision);
            final DirCache dirCache = DirCache.newInCore();
            final int numEdits = applyChanges(baseRevision, baseTreeId, dirCache, changes);
            if (numEdits == 0) {
                return Collections.emptyMap();
            }

            CanonicalTreeParser p = new CanonicalTreeParser();
            p.reset(reader, baseTreeId);
            diffFormatter.setRepository(jGitRepository);
            List<DiffEntry> result = diffFormatter.scan(p, new DirCacheIterator(dirCache));
            return toChangeMap(result);
        } catch (IOException e) {
            throw new StorageException("failed to perform a dry-run diff", e);
        }
    }

    private Map<String, Change<?>> toChangeMap(List<DiffEntry> diffEntryList) {

        try (ObjectReader reader = jGitRepository.newObjectReader()) {
            final Map<String, Change<?>> changeMap = new HashMap<>();

            for (DiffEntry diffEntry : diffEntryList) {
                final String oldPath = '/' + diffEntry.getOldPath();
                final String newPath = '/' + diffEntry.getNewPath();

                switch (diffEntry.getChangeType()) {
                case MODIFY:
                    final EntryType oldEntryType = EntryType.guessFromPath(oldPath);
                    switch (oldEntryType) {
                    case JSON:
                        JsonNode oldJsonNode = Jackson.readTree(
                                reader.open(diffEntry.getOldId().toObjectId()).getBytes());
                        JsonNode newJsonNode = Jackson.readTree(
                                reader.open(diffEntry.getNewId().toObjectId()).getBytes());

                        if (!oldPath.equals(newPath)) {
                            changeMap.put(oldPath, Change.ofRename(oldPath, newPath));
                        }

                        changeMap.put(newPath, Change.ofJsonPatch(newPath, oldJsonNode, newJsonNode));
                        break;
                    case TEXT:
                        final String oldText = new String(
                                reader.open(diffEntry.getOldId().toObjectId()).getBytes(),
                                StandardCharsets.UTF_8);

                        final String newText = new String(
                                reader.open(diffEntry.getNewId().toObjectId()).getBytes(),
                                StandardCharsets.UTF_8);

                        if (!oldPath.equals(newPath)) {
                            changeMap.put(oldPath, Change.ofRename(oldPath, newPath));
                        }

                        changeMap.put(newPath, Change.ofTextPatch(newPath, oldText, newText));
                        break;
                    default:
                        throw new Error("unexpected old entry type: " + oldEntryType);
                    }
                    break;
                case ADD:
                    final EntryType newEntryType = EntryType.guessFromPath(newPath);
                    switch (newEntryType) {
                    case JSON:
                        final JsonNode jsonNode = Jackson.readTree(
                                reader.open(diffEntry.getNewId().toObjectId()).getBytes());

                        changeMap.put(newPath, Change.ofJsonUpsert(newPath, jsonNode));
                        break;
                    case TEXT:
                        final String text = new String(
                                reader.open(diffEntry.getNewId().toObjectId()).getBytes(),
                                StandardCharsets.UTF_8);

                        changeMap.put(newPath, Change.ofTextUpsert(newPath, text));
                        break;
                    default:
                        throw new Error("unexpected new entry type: " + newEntryType);
                    }
                    break;
                case DELETE:
                    changeMap.put(oldPath, Change.ofRemoval(oldPath));
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

    @Override
    public CompletableFuture<Revision> commit(
            Revision baseRevision, Author author, String summary,
            String detail, Markup markup, Iterable<Change<?>> changes) {

        return CompletableFuture.supplyAsync(
                () -> blockingCommit(baseRevision, author, summary, detail, markup, changes), repositoryWorker);
    }

    private Revision blockingCommit(
            Revision baseRevision, Author author, String summary,
            String detail, Markup markup, Iterable<Change<?>> changes) {

        requireNonNull(baseRevision, "baseRevision");

        final CommitResult res;
        writeLock.lock();
        try {
            final Revision normBaseRevision = blockingNormalize(baseRevision);
            final Revision headRevision;
            final Revision nextRevision;
            final boolean pushToRunspace = normBaseRevision.minor() > 0;

            if (pushToRunspace) {
                headRevision = runspaceHeadRevision(baseRevision.major());
                if (headRevision.minor() != normBaseRevision.minor()) {
                    throw new ChangeConflictException(
                            "invalid baseRevision: " + baseRevision + " (expected: " + headRevision +
                            " or equivalent)");
                }
                nextRevision = new Revision(headRevision.major(), headRevision.minor() + 1);
            } else {
                headRevision = cachedMainLaneHeadRevision();
                if (headRevision.major() != normBaseRevision.major()) {
                    throw new ChangeConflictException(
                            "invalid baseRevision: " + baseRevision + " (expected: " + headRevision +
                            " or equivalent)");
                }
                nextRevision = new Revision(headRevision.major() + 1);
            }

            res = commit0(headRevision, nextRevision, author, summary, detail, markup, changes, false);

            if (!pushToRunspace) {
                this.headRevision = res.revision;
            }
        } finally {
            writeLock.unlock();
        }

        // Note that the notification is made while no lock is held to avoid the risk of a dead lock.
        notifyWatchers(res.revision, res.parentTreeId, res.treeId);
        return res.revision;
    }

    private CommitResult commit0(Revision prevRevision, Revision nextRevision,
                                 Author author, String summary, String detail, Markup markup,
                                 Iterable<Change<?>> changes, boolean allowEmpty) {

        requireNonNull(author, "author");
        requireNonNull(summary, "summary");
        requireNonNull(changes, "changes");
        requireNonNull(detail, "detail");
        requireNonNull(markup, "markup");

        assert prevRevision == null || prevRevision.major() > 0 && prevRevision.minor() >= 0;
        assert nextRevision.major() > 0 && nextRevision.minor() >= 0;

        try (ObjectInserter inserter = jGitRepository.newObjectInserter();
             ObjectReader reader = jGitRepository.newObjectReader();
             RevWalk revWalk = new RevWalk(reader)) {

            final ObjectId prevTreeId = prevRevision != null ? toTreeId(revWalk, prevRevision) : null;

            // The staging area that keeps the entries of the new tree.
            // It starts with the entries of the tree at the prevRevision (or with no entries if the
            // prevRevision is the initial commit), and then this method will apply the requested changes
            // to build the new tree.
            final DirCache dirCache = DirCache.newInCore();

            // Apply the changes and retrieve the list of the affected files.
            final int numEdits = applyChanges(prevRevision, prevTreeId, dirCache, changes);

            // Reject empty commit if necessary.
            if (!allowEmpty) {
                boolean isEmpty = numEdits == 0;
                if (!isEmpty) {
                    // Even if there are edits, the resulting tree might be identical with the previous tree.
                    CanonicalTreeParser p = new CanonicalTreeParser();
                    p.reset(reader, prevTreeId);
                    DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE);
                    diffFormatter.setRepository(jGitRepository);
                    isEmpty = diffFormatter.scan(p, new DirCacheIterator(dirCache)).isEmpty();
                }

                if (isEmpty) {
                    throw new RedundantChangeException("changes did not change anything: " + changes);
                }
            }

            // flush the current index to repository and get the result tree object id.
            final ObjectId nextTreeId = dirCache.writeTree(inserter);

            // build a commit object
            final PersonIdent personIdent = new PersonIdent(author.name(), author.email(),
                                                            System.currentTimeMillis() / 1000L * 1000L, 0);

            final CommitBuilder commitBuilder = new CommitBuilder();

            commitBuilder.setAuthor(personIdent);
            commitBuilder.setCommitter(personIdent);
            commitBuilder.setTreeId(nextTreeId);

            // Write summary, detail and revision to commit's message as JSON format.
            commitBuilder.setMessage(CommitUtil.toJsonString(summary, detail, markup, nextRevision));

            // if the head commit exists, use it as the parent commit.
            if (prevRevision != null) {
                commitBuilder.setParentId(toCommitId(prevRevision));
            }

            final ObjectId nextCommitId = inserter.insert(commitBuilder);
            inserter.flush();

            // tagging the revision object, for history lookup purpose.
            doRefUpdate(revisionRef(nextRevision), nextCommitId);

            if (nextRevision.onMainLane()) {
                doRefUpdate(R_HEADS_MASTER, nextCommitId);
            } else {
                doRefUpdate(runspaceRef(nextRevision.major()), nextCommitId);
            }

            return new CommitResult(nextRevision, prevTreeId, nextTreeId);
        } catch (IllegalArgumentException | StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to push at " + jGitRepository, e);
        }
    }

    private int applyChanges(Revision baseRevision, ObjectId baseTreeId, DirCache dirCache,
                             Iterable<Change<?>> changes) {

        int numEdits = 0;

        try (ObjectInserter inserter = jGitRepository.newObjectInserter();
             ObjectReader reader = jGitRepository.newObjectReader()) {

            if (baseTreeId != null) {
                // the DirCacheBuilder is to used for doing update operations on the given DirCache object
                final DirCacheBuilder builder = dirCache.builder();

                // Add the tree object indicated by the prevRevision to the temporary DirCache object.
                builder.addTree(EMPTY_BYTE, 0, reader, baseTreeId);
                builder.finish();
            }

            // loop over the specified changes.
            for (Change<?> change : changes) {
                final String changePath = change.path().substring(1); // Strip the leading '/'.
                final DirCacheEntry oldEntry = dirCache.getEntry(changePath);
                final byte[] oldContent = oldEntry != null ? reader.open(oldEntry.getObjectId()).getBytes()
                                                           : null;

                switch (change.type()) {
                case UPSERT_JSON:
                    applyPathEdit(dirCache, new PathEdit(changePath) {
                        @Override
                        public void apply(DirCacheEntry ent) {
                            ent.setFileMode(FileMode.REGULAR_FILE);
                            ent.setObjectId(newBlob(inserter, (JsonNode) change.content()));
                        }
                    });
                    numEdits++;
                    break;
                case UPSERT_TEXT: {
                    applyPathEdit(dirCache, new PathEdit(changePath) {
                        @Override
                        public void apply(DirCacheEntry ent) {
                            ent.setFileMode(FileMode.REGULAR_FILE);
                            ent.setObjectId(newBlob(
                                    inserter, ((String) change.content()).getBytes(StandardCharsets.UTF_8)));
                        }
                    });
                    numEdits++;
                    break;
                }
                case REMOVE:
                    if (oldEntry != null) {
                        applyPathEdit(dirCache, new DirCacheEditor.DeletePath(changePath));
                        numEdits++;
                        break;
                    }

                    // The path might be a directory.
                    if (applyDirectoryEdits(dirCache, changePath, null, change)) {
                        numEdits++;
                    } else {
                        // Was not a directory either; conflict.
                        reportNonExistentEntry(change);
                        break;
                    }
                    break;
                case RENAME: {
                    final String newPath = ((String) change.content()).substring(1); // Strip the leading '/'.

                    if (dirCache.getEntry(newPath) != null) {
                        throw new ChangeConflictException("a file exists at the target path: " + change);
                    }

                    if (oldEntry != null) {
                        if (changePath.equals(newPath)) {
                            // Redundant rename request - old path and new path are same.
                            break;
                        }

                        final DirCacheEditor editor = dirCache.editor();
                        editor.add(new DeletePath(changePath));
                        editor.add(new PathEdit(newPath) {
                            @Override
                            public void apply(DirCacheEntry ent) {
                                ent.setFileMode(oldEntry.getFileMode());
                                ent.setObjectId(oldEntry.getObjectId());
                            }
                        });
                        editor.finish();
                        numEdits++;
                        break;
                    }

                    // The path might be a directory.
                    if (applyDirectoryEdits(dirCache, changePath, newPath, change)) {
                        numEdits++;
                    } else {
                        // Was not a directory either; conflict.
                        reportNonExistentEntry(change);
                    }
                    break;
                }
                case APPLY_JSON_PATCH: {
                    final JsonNode oldJsonNode;
                    if (oldContent != null) {
                        oldJsonNode = Jackson.readTree(oldContent);
                    } else {
                        oldJsonNode = Jackson.nullNode;
                    }

                    final JsonNode newJsonNode;
                    try {
                        newJsonNode = JsonPatch.fromJson((JsonNode) change.content()).apply(oldJsonNode);
                    } catch (JsonPatchException e) {
                        throw new ChangeConflictException("failed to apply JSON patch: " + change, e);
                    }

                    applyPathEdit(dirCache, new PathEdit(changePath) {
                        @Override
                        public void apply(DirCacheEntry ent) {
                            ent.setFileMode(FileMode.REGULAR_FILE);
                            ent.setObjectId(newBlob(inserter, newJsonNode));
                        }
                    });
                    numEdits++;
                    break;
                }
                case APPLY_TEXT_PATCH:
                    Patch<String> patch =
                            DiffUtils.parseUnifiedDiff(Util.stringToLines((String) change.content()));
                    final List<String> oldText;
                    if (oldContent != null) {
                        oldText = Util.stringToLines(new String(oldContent, StandardCharsets.UTF_8));
                    } else {
                        oldText = Collections.emptyList();
                    }

                    final List<String> newText;
                    try {
                        newText = DiffUtils.patch(oldText, patch);
                    } catch (PatchFailedException e) {
                        throw new ChangeConflictException("failed to apply text patch: " + change, e);
                    }

                    applyPathEdit(dirCache, new PathEdit(changePath) {
                        @Override
                        public void apply(DirCacheEntry ent) {
                            ent.setFileMode(FileMode.REGULAR_FILE);
                            ent.setObjectId(newBlob(
                                    inserter, String.join("\n", newText).getBytes(StandardCharsets.UTF_8)));
                        }
                    });
                    numEdits++;
                    break;
                }
            }
        } catch (StorageException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to apply changes on revision " + baseRevision, e);
        }
        return numEdits;
    }

    private static void reportNonExistentEntry(Change<?> change) {
        throw new ChangeConflictException("non-existent file/directory: " + change);
    }

    private static ObjectId newBlob(ObjectInserter inserter, JsonNode content) {
        try {
            return newBlob(inserter, Jackson.writeValueAsBytes(content));
        } catch (IOException e) {
            throw new StorageException("failed to serialize a JSON value: " + content, e);
        }
    }

    private static ObjectId newBlob(ObjectInserter inserter, byte[] content) {
        final ObjectId id;
        try {
            id = inserter.insert(Constants.OBJ_BLOB, content);
        } catch (IOException e) {
            throw new StorageException("failed to create a new blob", e);
        }
        return id;
    }

    private static void applyPathEdit(DirCache dirCache, PathEdit edit) {
        final DirCacheEditor e = dirCache.editor();
        e.add(edit);
        e.finish();
    }

    /**
     * Applies recursive directory edits.
     *
     * @param oldDir the path to the directory to make a recursive change
     * @param newDir the path to the renamed directory, or {@code null} to remove the directory.
     *
     * @return {@code true} if any edits were made to {@code dirCache}, {@code false} otherwise
     */
    private static boolean applyDirectoryEdits(DirCache dirCache,
                                               String oldDir, String newDir, Change<?> change) {

        if (!oldDir.endsWith("/")) {
            oldDir += '/';
        }
        if (newDir != null && !newDir.endsWith("/")) {
            newDir += '/';
        }

        final byte[] rawOldDir = Constants.encode(oldDir);
        final byte[] rawNewDir = newDir != null ? Constants.encode(newDir) : null;
        final int numEntries = dirCache.getEntryCount();
        DirCacheEditor editor = null;

        loop: for (int i = 0; i < numEntries; i++) {
            final DirCacheEntry e = dirCache.getEntry(i);
            final byte[] rawPath = e.getRawPath();

            // Ensure that there are no entries under the newDir; we have a conflict otherwise.
            if (rawNewDir != null) {
                boolean conflict = true;
                if (rawPath.length > rawNewDir.length) {
                    // Check if there is a file whose path starts with 'newDir'.
                    for (int j = 0; j < rawNewDir.length; j++) {
                        if (rawNewDir[j] != rawPath[j]) {
                            conflict = false;
                            break;
                        }
                    }
                } else if (rawPath.length == rawNewDir.length - 1) {
                    // Check if there is a file whose path is exactly same with newDir without trailing '/'.
                    for (int j = 0; j < rawNewDir.length - 1; j++) {
                        if (rawNewDir[j] != rawPath[j]) {
                            conflict = false;
                            break;
                        }
                    }
                } else {
                    conflict = false;
                }

                if (conflict) {
                    throw new ChangeConflictException("target directory exists already: " + change);
                }
            }

            // Skip the entries that do not belong to the oldDir.
            if (rawPath.length <= rawOldDir.length) {
                continue;
            }
            for (int j = 0; j < rawOldDir.length; j++) {
                if (rawOldDir[j] != rawPath[j]) {
                    continue loop;
                }
            }

            // Do not create an editor until we find an entry to rename/remove.
            // We can tell if there was any matching entries or not from the nullness of editor later.
            if (editor == null) {
                editor = dirCache.editor();
                editor.add(new DeleteTree(oldDir));
                if (newDir == null) {
                    // Recursive removal
                    break;
                }
            }

            assert newDir != null; // We should get here only when it's a recursive rename.

            final String oldPath = e.getPathString();
            final String newPath = newDir + oldPath.substring(oldDir.length());
            editor.add(new PathEdit(newPath) {
                @Override
                public void apply(DirCacheEntry ent) {
                    ent.setFileMode(e.getFileMode());
                    ent.setObjectId(e.getObjectId());
                }
            });
        }

        if (editor != null) {
            editor.finish();
            return true;
        } else {
            return false;
        }
    }

    private void doRefUpdate(String ref, ObjectId commitId) throws IOException {
        doRefUpdate(jGitRepository, ref, commitId);
    }

    @VisibleForTesting
    static void doRefUpdate(org.eclipse.jgit.lib.Repository jGitRepository,
                            String ref, ObjectId commitId) throws IOException {
        if (ref.startsWith(Constants.R_TAGS)) {
            final Ref oldRef = jGitRepository.exactRef(ref);
            if (oldRef != null) {
                throw new StorageException("tag ref exists already: " + ref);
            }
        }

        final RefUpdate refUpdate = jGitRepository.updateRef(ref);
        refUpdate.setNewObjectId(commitId);

        final RefUpdate.Result res = refUpdate.update();
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
    public CompletableFuture<Revision> watch(Revision lastKnownRev, String pathPattern) {
        requireNonNull(lastKnownRev, "lastKnownRev");
        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(repositoryWorker, "executor");

        final CompletableFuture<Revision> future = new CompletableFuture<>();

        normalize(lastKnownRev).thenAccept(normalizedLastKnownRev -> {
            final Revision headRev;
            if (normalizedLastKnownRev.onMainLane()) {
                headRev = cachedMainLaneHeadRevision();
            } else {
                headRev = runspaceHeadRevision(normalizedLastKnownRev.major());
            }

            final PathPatternFilter filter = PathPatternFilter.of(pathPattern);

            // If lastKnownRev is outdated already and the recent changes match, there's no need to watch.
            if (!normalizedLastKnownRev.equals(headRev) &&
                hasMatchingChanges(normalizedLastKnownRev, headRev, filter)) {
                future.complete(headRev);
            } else {
                commitWatchers.add(normalizedLastKnownRev, filter, future);
            }
        }).exceptionally(cause -> {
            future.completeExceptionally(cause);
            return null;
        });

        return future;
    }

    private boolean hasMatchingChanges(Revision from, Revision to, PathPatternFilter filter) {
        try (RevWalk revWalk = new RevWalk(jGitRepository)) {
            final List<DiffEntry> diff =
                    compareTrees(toTreeId(revWalk, from), toTreeId(revWalk, to), TreeFilter.ALL);
            for (DiffEntry e : diff) {
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
                    return true;
                }
            }
        }

        return false;
    }

    private void notifyWatchers(Revision newRevision, ObjectId prevTreeId, ObjectId nextTreeId) {
        final List<DiffEntry> diff = compareTrees(prevTreeId, nextTreeId, TreeFilter.ALL);
        for (DiffEntry e: diff) {
            switch (e.getChangeType()) {
            case ADD:
                commitWatchers.notify(newRevision, e.getNewPath());
                break;
            case MODIFY:
            case DELETE:
                commitWatchers.notify(newRevision, e.getOldPath());
                break;
            default:
                throw new Error();
            }
        }
    }

    /**
     * Creates a new runspace at {@code majorRevision}.
     *
     * @param author author who creates this runspace
     * @param majorRevision the major revision number based on which the runspace is created
     * @return the {@link Revision} for newly created runspace
     */
    @Override
    public CompletableFuture<Revision> createRunspace(Author author, int majorRevision) {
        return CompletableFuture.supplyAsync(() -> blockingCreateRunspace(author, majorRevision),
                                             repositoryWorker);
    }

    private Revision blockingCreateRunspace(Author author, int majorRevision) {
        if (majorRevision <= 0) {
            throw new IllegalArgumentException("majorRevision " + majorRevision + " (expected: > 0)");
        }

        final Revision prevRevision;
        final Revision nextRevision;

        writeLock.lock();
        try {
            if (resolveExactRef(runspaceRef(majorRevision)) != null) {
                throw new StorageException("runspace exists already (majorRevision: " + majorRevision + ')');
            }

            prevRevision = new Revision(majorRevision);

            if (resolveExactRef(revisionRef(prevRevision)) == null) {
                throw new RevisionNotFoundException("non-existent majorRevision: " + majorRevision);
            }

            nextRevision = new Revision(majorRevision, 1);

            return commit0(prevRevision, nextRevision, author, "Create a new runspace from " + majorRevision,
                           "", Markup.PLAINTEXT, Collections.emptyList(), true).revision;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Removes the runspace associated with the specified {@code majorRevision}.
     */
    @Override
    public CompletableFuture<Void> removeRunspace(int majorRevision) {
        return CompletableFuture.supplyAsync(() -> {
            blockingRemoveRunspace(majorRevision);
            return null;
        }, repositoryWorker);
    }

    private void blockingRemoveRunspace(int majorRevision) {
        writeLock.lock();
        try {
            final String oldRef = runspaceRef(majorRevision);
            if (jGitRepository.exactRef(oldRef) == null) {
                throw new StorageException("non-existent runspace for major revision: " + majorRevision);
            }

            final RefRename refRename =
                    jGitRepository.renameRef(oldRef, removedRunspaceRef(majorRevision));
            final Result result = refRename.rename();
            if (Result.RENAMED != result) {
                throw new StorageException(
                        "failed to remove the runspace for major revision: " + majorRevision +
                        " (result: " + result + ')');
            }

            final Map<String, Ref> refMap = jGitRepository.getRefDatabase().getRefs(
                    Constants.R_TAGS + TagUtil.byteHexDirName(majorRevision));

            for (Map.Entry<String, Ref> refEntry : refMap.entrySet()) {
                final Revision revision = new Revision(refEntry.getKey());
                if (revision.major() == majorRevision && !revision.onMainLane()) {
                    RefUpdate refUpdate = jGitRepository.updateRef(refEntry.getValue().getName());
                    refUpdate.setForceUpdate(true);
                    refUpdate.delete();
                }
            }
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to remove runspace for major revision: " + majorRevision, e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public CompletableFuture<Set<Revision>> listRunspaces() {
        return CompletableFuture.supplyAsync(this::blockingListRunspaces, repositoryWorker);
    }

    private Set<Revision> blockingListRunspaces() {
        final Set<Revision> runspaces = new HashSet<>();
        try (RevWalk revWalk = new RevWalk(jGitRepository)) {
            final Map<String, Ref> refMap = jGitRepository.getRefDatabase().getRefs(R_HEADS_RUNSPACES);
            for (Ref ref : refMap.values()) {
                RevCommit revCommit = revWalk.parseCommit(ref.getObjectId());
                Revision revision = CommitUtil.extractRevision(revCommit.getFullMessage());
                runspaces.add(revision);
            }
            return runspaces;
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to list runspaces", e);
        }
    }

    private Revision cachedMainLaneHeadRevision() {
        return headRevision;
    }

    /**
     * Returns the current revision.
     */
    private Revision uncachedMainLaneHeadRevision() {
        try (RevWalk revWalk = new RevWalk(jGitRepository)) {
            ObjectId headRevisionId = jGitRepository.resolve(R_HEADS_MASTER);
            if (headRevisionId != null) {
                RevCommit revCommit = revWalk.parseCommit(headRevisionId);
                return CommitUtil.extractRevision(revCommit.getFullMessage());
            }
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to get the current revision", e);
        }

        throw new StorageException("failed to determine the HEAD: " + jGitRepository.getDirectory());
    }

    /**
     * Get the latest revision for given runspace specified by {@code major}.
     */
    private Revision runspaceHeadRevision(int major) {
        if (major == 0) {
            throw new IllegalArgumentException("major: 0 (expected: a positive integer)");
        }

        try (RevWalk revWalk = new RevWalk(jGitRepository)) {
            ObjectId commitId = toRunspaceHeadCommitId(major);
            RevCommit revCommit = revWalk.parseCommit(commitId);
            return CommitUtil.extractRevision(revCommit.getFullMessage());
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(
                    "failed to get the latest runspace revision for major revision " + major, e);
        }
    }

    /**
     * Compares the old tree and the new tree to get the list of the affected files.
     */
    private List<DiffEntry> compareTrees(ObjectId prevTreeId, ObjectId nextTreeId, TreeFilter filter) {
        try (DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE)) {
            diffFormatter.setRepository(jGitRepository);
            diffFormatter.setPathFilter(filter);

            return diffFormatter.scan(prevTreeId, nextTreeId);
        } catch (IOException e) {
            throw new StorageException("failed to compare two trees: " + prevTreeId + " vs. " + nextTreeId, e);
        }
    }

    private ObjectId toTreeId(RevWalk revWalk, Revision revision) {
        final ObjectId commitId = toCommitId(revision);
        try {
            return revWalk.parseCommit(commitId).getTree().getId();
        } catch (IOException e) {
            throw new StorageException("failed to parse a commit: " + commitId, e);
        }
    }

    private ObjectId toCommitId(Revision revision) {
        requireNonNull(revision, "revision");
        final ObjectId resolved = resolveExactRef(revisionRef(revision));
        if (resolved == null) {
            throw new StorageException("failed to find the commit for the revision: " + revision);
        }
        return resolved;
    }

    private ObjectId toRunspaceHeadCommitId(int major) {
        final ObjectId resolved = resolveExactRef(runspaceRef(major));
        if (resolved == null) {
            throw new StorageException("failed to find the head commit for the runspace: " + major);
        }
        return resolved;
    }

    private ObjectId resolveExactRef(String ref) {
        try {
            final Ref res = jGitRepository.exactRef(requireNonNull(ref, "ref"));
            return res != null ? res.getObjectId() : null;
        } catch (IOException e) {
            throw new StorageException("failed to resolve: " + ref, e);
        }
    }

    private static String revisionRef(Revision revision) {
        return Constants.R_TAGS + TagUtil.byteHexDirName(revision.major()) +
               (revision.minor() == 0 ? revision.text() + ".0" : revision.text());
    }

    private static String runspaceRef(int majorRevision) {
        return R_HEADS_RUNSPACES + majorRevision;
    }

    private static String removedRunspaceRef(int majorRevision) {
        return R_HEADS_RUNSPACES_REMOVED + majorRevision;
    }

    @Override
    public String toString() {
        return "GitRepository(" + jGitRepository.getDirectory() + ')';
    }

    private static final class CommitResult {
        final Revision revision;
        final ObjectId parentTreeId;
        final ObjectId treeId;

        CommitResult(Revision revision, ObjectId parentTreeId, ObjectId treeId) {
            this.revision = revision;
            this.parentTreeId = parentTreeId;
            this.treeId = treeId;
        }
    }
}
