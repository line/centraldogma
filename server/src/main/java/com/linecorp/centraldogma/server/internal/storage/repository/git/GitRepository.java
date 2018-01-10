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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_DIFF_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_ALGORITHM;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_FILEMODE;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_HIDEDOTFILES;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_RENAMES;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_REPO_FORMAT_VERSION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_SYMLINKS;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

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
import org.eclipse.jgit.internal.storage.file.RefDirectory;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CoreConfig.HideDotFiles;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
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
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceMode;
import com.linecorp.centraldogma.server.internal.storage.StorageException;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.ChangeConflictException;
import com.linecorp.centraldogma.server.internal.storage.repository.FindOption;
import com.linecorp.centraldogma.server.internal.storage.repository.RedundantChangeException;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;
import com.linecorp.centraldogma.server.internal.storage.repository.RevisionNotFoundException;

import difflib.DiffUtils;
import difflib.Patch;

/**
 * A {@link Repository} based on Git.
 */
class GitRepository implements Repository {

    private static final Logger logger = LoggerFactory.getLogger(GitRepository.class);

    static final String R_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;

    private static final byte[] EMPTY_BYTE = new byte[0];
    private static final Pattern CR = Pattern.compile("\r", Pattern.LITERAL);

    private final Lock writeLock = new ReentrantLock();
    private final Project parent;
    private final Executor repositoryWorker;
    private final String name;
    private final org.eclipse.jgit.lib.Repository jGitRepository;
    private final GitRepositoryFormat format;
    private final CommitIdDatabase commitIdDatabase;
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
     * @param creationTimeMillis the creation time
     * @param author the user who initiated the creation of this repository
     *
     * @throws StorageException if failed to create a new repository
     */
    GitRepository(Project parent, File repoDir, Executor repositoryWorker,
                  long creationTimeMillis, Author author) {
        this(parent, repoDir, GitRepositoryFormat.V1, repositoryWorker, creationTimeMillis, author);
    }

    /**
     * Creates a new Git-backed repository.
     *
     * @param repoDir the location of this repository
     * @param format the repository format
     * @param repositoryWorker the {@link Executor} which will perform the blocking repository operations
     * @param creationTimeMillis the creation time
     * @param author the user who initiated the creation of this repository
     *
     * @throws StorageException if failed to create a new repository
     */
    GitRepository(Project parent, File repoDir, GitRepositoryFormat format, Executor repositoryWorker,
                  long creationTimeMillis, Author author) {

        this.parent = requireNonNull(parent, "parent");
        name = requireNonNull(repoDir, "repoDir").getName();
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
        this.format = requireNonNull(format, "format");

        requireNonNull(author, "author");

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

                final StoredConfig config = initRepo.getConfig();
                if (format == GitRepositoryFormat.V1) {
                    // Update the repository settings to upgrade to format version 1 and reftree.
                    config.setInt(CONFIG_CORE_SECTION, null, CONFIG_KEY_REPO_FORMAT_VERSION, 1);
                }

                // Disable hidden files, symlinks and file modes we do not use.
                config.setEnum(CONFIG_CORE_SECTION, null, CONFIG_KEY_HIDEDOTFILES, HideDotFiles.FALSE);
                config.setBoolean(CONFIG_CORE_SECTION, null, CONFIG_KEY_SYMLINKS, false);
                config.setBoolean(CONFIG_CORE_SECTION, null, CONFIG_KEY_FILEMODE, false);

                // Set the diff algorithm.
                config.setString(CONFIG_DIFF_SECTION, null, CONFIG_KEY_ALGORITHM, "histogram");

                // Disable rename detection which we do not use.
                config.setBoolean(CONFIG_DIFF_SECTION, null, CONFIG_KEY_RENAMES, false);

                config.save();
            }

            // Re-open the repository with the updated settings and format version.
            jGitRepository = new RepositoryBuilder().setGitDir(repoDir).build();

            // Initialize the master branch.
            final RefUpdate head = jGitRepository.updateRef(Constants.HEAD);
            head.disableRefLog();
            head.link(Constants.R_HEADS + Constants.MASTER);

            // Initialize the commit ID database.
            commitIdDatabase = new CommitIdDatabase(jGitRepository);

            // Insert the initial commit into the master branch.
            commit0(null, Revision.INIT, creationTimeMillis, author,
                    "Create a new repository", "", Markup.PLAINTEXT,
                    Collections.emptyList(), true);

            headRevision = Revision.INIT;
            success = true;
        } catch (IOException e) {
            throw new StorageException("failed to create a repository at: " + repoDir, e);
        } finally {
            if (!success) {
                close();
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

            // Retrieve the tag format.
            final int formatVersion = jGitRepository.getConfig().getInt(
                    CONFIG_CORE_SECTION, null, CONFIG_KEY_REPO_FORMAT_VERSION, 0);
            switch (formatVersion) {
                case 0:
                    format = GitRepositoryFormat.V0;
                    break;
                case 1:
                    format = GitRepositoryFormat.V1;
                    break;
                default:
                    throw new StorageException("unknown repository format version: " + formatVersion);
            }
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
            success = true;
        } finally {
            if (!success) {
                close();
            }
        }
    }

    private static boolean exist(File repoDir) {
        try {
            RepositoryBuilder repositoryBuilder = new RepositoryBuilder().setGitDir(repoDir);
            org.eclipse.jgit.lib.Repository repository = repositoryBuilder.build();
            if (repository.getConfig() instanceof FileBasedConfig) {
                return ((FileBasedConfig) repository.getConfig()).getFile().exists();
            }
            return repository.getDirectory().exists();
        } catch (IOException e) {
            throw new StorageException("failed to check if repository exists at " + repoDir, e);
        }
    }

    void close() {
        if (commitIdDatabase != null) {
            commitIdDatabase.close();
        }
        if (jGitRepository != null) {
            try {
                jGitRepository.close();
            } catch (Exception e) {
                logger.warn("Failed to close a Git repository: {}", jGitRepository.getDirectory(), e);
            }
        }
    }

    @Override
    public Project parent() {
        return parent;
    }

    @Override
    public String name() {
        return name;
    }

    public GitRepositoryFormat format() {
        return format;
    }

    public boolean needsMigration(GitRepositoryFormat preferredFormat) {
        if (format != preferredFormat) {
            return true;
        }

        if (!(jGitRepository.getRefDatabase() instanceof RefDirectory)) {
            // 0.19.0 used RefTreeDatabase we do not use anymore.
            return true;
        }

        final File oldTagFile = new File(
                jGitRepository.getDirectory(),
                "refs" + File.separatorChar + "tags" + File.separatorChar + "01" + File.separatorChar + "1.0");

        // Some old repositories used tags to store the revision-to-commitId mappings,
        // which has been replaced by CommitIdDatabase by https://github.com/line/centraldogma/pull/104
        return oldTagFile.exists();
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

        return CompletableFuture.supplyAsync(() -> blockingFind(revision, pathPattern, options),
                                             repositoryWorker);
    }

    private Map<String, Entry<?>> blockingFind(
            Revision revision, String pathPattern, Map<FindOption<?>, ?> options) {

        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(revision, "revision");
        requireNonNull(options, "options");

        final Revision normRevision = normalizeNow(revision);
        final boolean fetchContent = FindOption.FETCH_CONTENT.get(options);
        final int maxEntries = FindOption.MAX_ENTRIES.get(options);

        try (ObjectReader reader = jGitRepository.newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = new RevWalk(reader)) {

            // Query on a non-exist revision will return empty result.
            final Revision headRevision = cachedHeadRevision();
            if (normRevision.compareTo(headRevision) > 0) {
                return Collections.emptyMap();
            }

            if ("/".equals(pathPattern)) {
                return Collections.singletonMap(pathPattern, Entry.rootDir());
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
                        final String strVal = sanitizeText(new String(content, UTF_8));
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

        final RevisionRange range = normalizeNow(from, to);
        final RevisionRange descendingRange = range.toDescending();

        final ObjectId fromCommitId = commitIdDatabase.get(descendingRange.from());
        final ObjectId toCommitId = commitIdDatabase.get(descendingRange.to());

        // At this point, we are sure: from.major >= to.major
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

            final List<Commit> commitList = new ArrayList<>();
            boolean needsLastCommit = true;
            for (RevCommit revCommit : revWalk) {
                final Commit c = toCommit(revCommit);
                if (c != null) {
                    commitList.add(c);
                } else {
                    // Probably garbage?
                    continue;
                }

                if (revCommit.getId().equals(toCommitId) || commitList.size() == maxCommits) {
                    // Visited the last commit or can't retrieve beyond maxCommits
                    needsLastCommit = false;
                    break;
                }
            }

            // Handle the case where the last commit was not visited by the RevWalk,
            // which can happen when the commit is empty.  In our repository, an empty commit can only be made
            // when a new repository is created.
            // If the pathPattern does not contain "/**", the caller wants commits only with the specific path,
            // so skip the empty commit.
            if (needsLastCommit && pathPattern.contains(ALL_PATH)) {
                try (RevWalk tmpRevWalk = new RevWalk(jGitRepository)) {
                    final RevCommit lastRevCommit = tmpRevWalk.parseCommit(toCommitId);
                    final Revision lastCommitRevision =
                            CommitUtil.extractRevision(lastRevCommit.getFullMessage());
                    if (lastCommitRevision.major() == 1) {
                        commitList.add(toCommit(lastRevCommit));
                    }
                }
            }

            if (!descendingRange.equals(range)) { // from and to is swapped so reverse the list.
                Collections.reverse(commitList);
            }

            return commitList;
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(
                    "failed to retrieve the history: " + jGitRepository +
                    " (" + pathPattern + ", " + from + ".." + to + ')', e);
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

        final RevisionRange range = normalizeNow(from, to).toAscending();

        return toChangeMap(compareTrees(commitIdDatabase.get(range.from()),
                                        commitIdDatabase.get(range.to()),
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
        baseRevision = normalizeNow(baseRevision);

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
                                Jackson.readTree(reader.open(diffEntry.getOldId().toObjectId()).getBytes());
                        final JsonNode newJsonNode =
                                Jackson.readTree(reader.open(diffEntry.getNewId().toObjectId()).getBytes());
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
                            putChange(changeMap, newPath, Change.ofTextPatch(newPath, oldText, newText));
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
    public CompletableFuture<Revision> commit(
            Revision baseRevision, long commitTimeMillis, Author author, String summary,
            String detail, Markup markup, Iterable<Change<?>> changes) {

        return CompletableFuture.supplyAsync(
                () -> blockingCommit(baseRevision, commitTimeMillis,
                                     author, summary, detail, markup, changes, false), repositoryWorker);
    }

    private Revision blockingCommit(
            Revision baseRevision, long commitTimeMillis, Author author, String summary,
            String detail, Markup markup, Iterable<Change<?>> changes, boolean allowEmptyCommit) {

        requireNonNull(baseRevision, "baseRevision");

        final CommitResult res;
        writeLock.lock();
        try {
            final Revision normBaseRevision = normalizeNow(baseRevision);
            final Revision headRevision = cachedHeadRevision();
            if (headRevision.major() != normBaseRevision.major()) {
                throw new ChangeConflictException(
                        "invalid baseRevision: " + baseRevision + " (expected: " + headRevision +
                        " or equivalent)");
            }

            res = commit0(headRevision, headRevision.forward(1), commitTimeMillis,
                          author, summary, detail, markup, changes, allowEmptyCommit);

            this.headRevision = res.revision;
        } finally {
            writeLock.unlock();
        }

        // Note that the notification is made while no lock is held to avoid the risk of a dead lock.
        notifyWatchers(res.revision, res.parentTreeId, res.treeId);
        return res.revision;
    }

    private CommitResult commit0(Revision prevRevision, Revision nextRevision, long commitTimeMillis,
                                 Author author, String summary, String detail, Markup markup,
                                 Iterable<Change<?>> changes, boolean allowEmpty) {

        requireNonNull(author, "author");
        requireNonNull(summary, "summary");
        requireNonNull(changes, "changes");
        requireNonNull(detail, "detail");
        requireNonNull(markup, "markup");

        assert prevRevision == null || prevRevision.major() > 0;
        assert nextRevision.major() > 0;

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
            doRefUpdate(revWalk, R_HEADS_MASTER, nextCommitId);

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
                case UPSERT_JSON: {
                    final JsonNode oldJsonNode = oldContent != null ? Jackson.readTree(oldContent) : null;
                    final JsonNode newJsonNode = (JsonNode) change.content();

                    // Upsert only when the contents are really different.
                    if (!newJsonNode.equals(oldJsonNode)) {
                        applyPathEdit(dirCache, new PathEdit(changePath) {
                            @Override
                            public void apply(DirCacheEntry ent) {
                                ent.setFileMode(FileMode.REGULAR_FILE);
                                ent.setObjectId(newBlob(inserter, newJsonNode));
                            }
                        });
                        numEdits++;
                    }
                    break;
                }
                case UPSERT_TEXT: {
                    final String sanitizedOldText;
                    if (oldContent != null) {
                        sanitizedOldText = sanitizeText(new String(oldContent, UTF_8));
                    } else {
                        sanitizedOldText = null;
                    }

                    final String sanitizedNewText = sanitizeText(change.contentAsText());

                    // Upsert only when the contents are really different.
                    if (!sanitizedNewText.equals(sanitizedOldText)) {
                        applyPathEdit(dirCache, new PathEdit(changePath) {
                            @Override
                            public void apply(DirCacheEntry ent) {
                                ent.setFileMode(FileMode.REGULAR_FILE);
                                ent.setObjectId(newBlob(inserter, sanitizedNewText.getBytes(UTF_8)));
                            }
                        });
                        numEdits++;
                    }
                    break;
                }
                case REMOVE:
                    if (oldEntry != null) {
                        applyPathEdit(dirCache, new DeletePath(changePath));
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
                    } catch (Exception e) {
                        throw new ChangeConflictException("failed to apply JSON patch: " + change, e);
                    }

                    // Apply only when the contents are really different.
                    if (!newJsonNode.equals(oldJsonNode)) {
                        applyPathEdit(dirCache, new PathEdit(changePath) {
                            @Override
                            public void apply(DirCacheEntry ent) {
                                ent.setFileMode(FileMode.REGULAR_FILE);
                                ent.setObjectId(newBlob(inserter, newJsonNode));
                            }
                        });
                        numEdits++;
                    }
                    break;
                }
                case APPLY_TEXT_PATCH:
                    final Patch<String> patch = DiffUtils.parseUnifiedDiff(
                            Util.stringToLines(sanitizeText((String) change.content())));

                    final String sanitizedOldText;
                    final List<String> sanitizedOldTextLines;
                    if (oldContent != null) {
                        sanitizedOldText = sanitizeText(new String(oldContent, UTF_8));
                        sanitizedOldTextLines = Util.stringToLines(sanitizedOldText);
                    } else {
                        sanitizedOldText = null;
                        sanitizedOldTextLines = Collections.emptyList();
                    }

                    final String newText;
                    try {
                        final List<String> newTextLines = DiffUtils.patch(sanitizedOldTextLines, patch);
                        if (newTextLines.isEmpty()) {
                            newText = "";
                        } else {
                            final StringJoiner joiner = new StringJoiner("\n", "", "\n");
                            for (String line : newTextLines) {
                                joiner.add(line);
                            }
                            newText = joiner.toString();
                        }
                    } catch (Exception e) {
                        throw new ChangeConflictException("failed to apply text patch: " + change, e);
                    }

                    // Apply only when the contents are really different.
                    if (!newText.equals(sanitizedOldText)) {
                        applyPathEdit(dirCache, new PathEdit(changePath) {
                            @Override
                            public void apply(DirCacheEntry ent) {
                                ent.setFileMode(FileMode.REGULAR_FILE);
                                ent.setObjectId(newBlob(inserter, newText.getBytes(UTF_8)));
                            }
                        });
                        numEdits++;
                    }
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

    private void doRefUpdate(RevWalk revWalk, String ref, ObjectId commitId) throws IOException {
        doRefUpdate(jGitRepository, revWalk, ref, commitId);
    }

    @VisibleForTesting
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
    public CompletableFuture<Revision> watch(Revision lastKnownRevision, String pathPattern) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(repositoryWorker, "executor");

        final CompletableFuture<Revision> future = new CompletableFuture<>();

        normalize(lastKnownRevision).thenAccept(normLastKnownRevision -> {
            final Revision headRevision = cachedHeadRevision();
            final PathPatternFilter filter = PathPatternFilter.of(pathPattern);

            // If lastKnownRevision is outdated already and the recent changes match, there's no need to watch.
            if (!normLastKnownRevision.equals(headRevision) &&
                hasMatchingChanges(normLastKnownRevision, headRevision, filter)) {
                future.complete(headRevision);
            } else {
                commitWatchers.add(normLastKnownRevision, filter, future);
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

    private Revision cachedHeadRevision() {
        return headRevision;
    }

    /**
     * Returns the current revision.
     */
    private Revision uncachedHeadRevision() {
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
        final ObjectId commitId = commitIdDatabase.get(revision);
        try {
            return revWalk.parseCommit(commitId).getTree().getId();
        } catch (IOException e) {
            throw new StorageException("failed to parse a commit: " + commitId, e);
        }
    }

    /**
     * Clones this repository into a new one.
     */
    public void cloneTo(File newRepoDir) {
        cloneTo(newRepoDir, GitRepositoryFormat.V1);
    }

    /**
     * Clones this repository into a new one.
     */
    public void cloneTo(File newRepoDir, BiConsumer<Integer, Integer> progressListener) {
        cloneTo(newRepoDir, GitRepositoryFormat.V1, progressListener);
    }

    /**
     * Clones this repository into a new one.
     *
     * @param format the repository format
     */
    public void cloneTo(File newRepoDir, GitRepositoryFormat format) {
        cloneTo(newRepoDir, format, (current, total) -> { /* no-op */ });
    }

    /**
     * Clones this repository into a new one.
     *
     * @param format the repository format
     */
    public void cloneTo(File newRepoDir, GitRepositoryFormat format,
                        BiConsumer<Integer, Integer> progressListener) {

        requireNonNull(newRepoDir, "newRepoDir");
        requireNonNull(format, "format");
        requireNonNull(progressListener, "progressListener");

        final Revision endRevision = normalizeNow(Revision.HEAD);
        final GitRepository newRepo = new GitRepository(parent, newRepoDir, format, repositoryWorker,
                                                        creationTimeMillis(), author());

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
                        Repository.ALL_PATH, batch);
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
                            blockingDiff(previousNonEmptyRevision, revision, Repository.ALL_PATH).values();

                    try {
                        newRepo.blockingCommit(
                                baseRevision, c.when(), c.author(), c.summary(), c.detail(), c.markup(),
                                changes, /* allowEmptyCommit */ false);
                        previousNonEmptyRevision = revision;
                    } catch (RedundantChangeException e) {
                        // NB: We allow an empty commit here because an old version of Central Dogma had a bug
                        //     which allowed the creation of an empty commit.
                        newRepo.blockingCommit(
                                baseRevision, c.when(), c.author(), c.summary(), c.detail(), c.markup(),
                                changes, /* allowEmptyCommit */ true);
                    }

                    progressListener.accept(i, endRevision.major());
                    i++;
                }
            }

            success = true;
        } finally {
            newRepo.close();
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
                          .add("format", format)
                          .toString();
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
