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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.closeJGitRepo;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.exists;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryV2.R_HEADS_MASTER;
import static com.linecorp.centraldogma.server.storage.repository.Repository.ALL_PATH;
import static com.linecorp.centraldogma.server.storage.repository.Repository.MAX_MAX_COMMITS;
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
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

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
import org.eclipse.jgit.lib.CoreConfig.HideDotFiles;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdOwnerMap;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.TreeRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

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
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceMode;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.FindOption;

import difflib.DiffUtils;
import difflib.Patch;

final class InternalRepository {

    private static final Logger logger = LoggerFactory.getLogger(InternalRepository.class);

    private static final Pattern CR = Pattern.compile("\r", Pattern.LITERAL);
    private static final byte[] EMPTY_BYTE = new byte[0];

    private static final Field revWalkObjectsField;

    static {
        try {
            revWalkObjectsField = RevWalk.class.getDeclaredField("objects");
            if (revWalkObjectsField.getType() != ObjectIdOwnerMap.class) {
                throw new IllegalStateException(
                        RevWalk.class.getSimpleName() + ".objects is not an " +
                        ObjectIdOwnerMap.class.getSimpleName() + '.');
            }
            revWalkObjectsField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    RevWalk.class.getSimpleName() + ".objects does not exist.");
        }
    }

    static InternalRepository of(Project parent, String originalRepoName, File repoDir,
                                 Revision nextRevision, long commitTimeMillis,
                                 Author author, Iterable<Change<?>> changes) {
        boolean success = false;
        InternalRepository internalRepo = null;
        try {
            createEmptyJGitRepo(repoDir);

            // Re-open the repository with the updated settings and format version.
            final Repository jGitRepo = new RepositoryBuilder().setGitDir(repoDir).build();
            internalRepo = new InternalRepository(parent, originalRepoName, repoDir,
                                                  jGitRepo, new CommitIdDatabase(jGitRepo));

            // Initialize the master branch.
            final RefUpdate head = jGitRepo.updateRef(Constants.HEAD);
            head.disableRefLog();
            head.link(R_HEADS_MASTER);

            // Insert the initial commit into the master branch.
            internalRepo.commit(null, nextRevision, commitTimeMillis, author,
                                "Create a new repository", "", Markup.PLAINTEXT, changes, true);
            success = true;
            return internalRepo;
        } catch (IOException e) {
            throw new StorageException("failed to create a repository at: " + repoDir, e);
        } finally {
            if (!success) {
                if (internalRepo != null) {
                    internalRepo.close();
                }
                // Failed to create a repository. Remove any cruft so that it is not loaded on the next run.
                deleteCruft(repoDir);
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

    private static void createEmptyJGitRepo(File repositoryDir) throws IOException {
        try (org.eclipse.jgit.lib.Repository initRepository = buildJGitRepo(repositoryDir)) {
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

    static Repository buildJGitRepo(File repositoryDir) {
        try {
            return new RepositoryBuilder().setGitDir(repositoryDir).setBare().build();
        } catch (IOException e) {
            throw new StorageException("failed to create a repository at: " + repositoryDir, e);
        }
    }

    @Nullable
    static InternalRepository open(Project parent, String name, File repoDir, boolean errorIfNotExist) {
        final Repository jGitRepo = buildJGitRepo(repoDir);
        CommitIdDatabase commitIdDatabase = null;
        try {
            if (!exists(jGitRepo)) {
                if (errorIfNotExist) {
                    throw new RepositoryNotFoundException(repoDir.toString());
                } else {
                    return null;
                }
            }
            checkGitRepositoryFormat(jGitRepo);
            final Revision headRevision = uncachedHeadRevision(parent, name, jGitRepo);
            commitIdDatabase = new CommitIdDatabase(jGitRepo);
            if (!headRevision.equals(commitIdDatabase.headRevision())) {
                commitIdDatabase.rebuild(jGitRepo);
                assert headRevision.equals(commitIdDatabase.headRevision());
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

    private static Revision uncachedHeadRevision(Project parent, String name, Repository jGitRepo) {
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

    private static RevWalk newRevWalk(Repository jGitRepository) {
        final RevWalk revWalk = new RevWalk(jGitRepository);
        // Disable rewriteParents because otherwise `RevWalk` will load every commit into memory.
        revWalk.setRewriteParents(false);
        return revWalk;
    }

    private static RevWalk newRevWalk(ObjectReader reader) {
        final RevWalk revWalk = new RevWalk(reader);
        // Disable rewriteParents because otherwise `RevWalk` will load every commit into memory.
        revWalk.setRewriteParents(false);
        return revWalk;
    }

    private static void checkGitRepositoryFormat(Repository repository) {
        final int formatVersion = repository.getConfig().getInt(
                CONFIG_CORE_SECTION, null, CONFIG_KEY_REPO_FORMAT_VERSION, 0);
        if (formatVersion != 1) {
            throw new StorageException("unsupported repository format version: " + formatVersion);
        }
    }

    private final Project project;
    private final String originalRepoName; // e.g. foo
    private final File repoDir;            // e.g. foo_0000000000
    private final Repository jGitRepository;
    private final CommitIdDatabase commitIdDatabase;

    // Only accessed by the worker in CommitRetentionManagementPlugin.
    @Nullable
    private Instant secondCommitCreationTimeInstant;

    private InternalRepository(Project project, String originalRepoName, File repoDir,
                               Repository jGitRepository, CommitIdDatabase commitIdDatabase) {
        this.project = project;
        this.originalRepoName = originalRepoName;
        this.repoDir = repoDir;
        this.jGitRepository = jGitRepository;
        this.commitIdDatabase = commitIdDatabase;
    }

    Project project() {
        return project;
    }

    String originalRepoName() {
        return originalRepoName;
    }

    File repoDir() {
        return repoDir;
    }

    Repository jGitRepo() {
        return jGitRepository;
    }

    CommitIdDatabase commitIdDatabase() {
        return commitIdDatabase;
    }

    Revision headRevision() {
        final Revision headRevision = commitIdDatabase.headRevision();
        checkState(headRevision != null, "the headRevision is not set.");
        return headRevision;
    }

    Revision firstRevision() {
        final Revision firstRevision = commitIdDatabase.firstRevision();
        checkState(firstRevision != null, "the firstRevision is not set.");
        return firstRevision;
    }

    /**
     * Returns the {@link Instant} of the time when the second commit is created. This {@link Instant} is used
     * to check if the second commit of this repository exceeds the minimum retention days or not.
     */
    @Nullable
    Instant secondCommitCreationTimeInstant() {
        if (secondCommitCreationTimeInstant == null) {
            final Revision firstRevision = commitIdDatabase.firstRevision();
            if (firstRevision == null) {
                return null;
            }
            final Revision headRevision = commitIdDatabase.headRevision();
            if (headRevision == null) {
                return null;
            }
            if (firstRevision.equals(headRevision)) {
                // The second commit is not made yet.
                return null;
            }
            final Revision secondRevision = firstRevision.forward(1);
            final RevisionRange range = new RevisionRange(secondRevision, secondRevision);
            secondCommitCreationTimeInstant =
                    Instant.ofEpochMilli(listCommits(ALL_PATH, 1, range).get(0).when());
        }
        return secondCommitCreationTimeInstant;
    }

    List<Commit> listCommits(String pathPattern, int maxCommits, RevisionRange range) {
        final RevisionRange descendingRange = range.toDescending();
        try (RevWalk revWalk = newRevWalk(jGitRepository)) {
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
                try (RevWalk tmpRevWalk = newRevWalk(jGitRepository)) {
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
                    "failed to retrieve the history: " + originalRepoName +
                    " (" + pathPattern + ", " + range.from() + ".." + range.to() + ')', e);
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

    Map<String, Entry<?>> find(Revision normalizedRevision, String pathPattern, Map<FindOption<?>, ?> options) {
        try (ObjectReader reader = jGitRepository.newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = newRevWalk(reader)) {
            final Map<String, Entry<?>> result = new LinkedHashMap<>();
            final ObjectId commitId = commitIdDatabase.get(normalizedRevision);
            final RevCommit revCommit = revWalk.parseCommit(commitId);
            final PathPatternFilter filter = PathPatternFilter.of(pathPattern);

            final int maxEntries = FindOption.MAX_ENTRIES.get(options);
            final RevTree revTree = revCommit.getTree();
            treeWalk.addTree(revTree.getId());
            while (treeWalk.next() && result.size() < maxEntries) {
                final boolean matches = filter.matches(treeWalk);
                final String path = '/' + treeWalk.getPathString();

                // Recurse into a directory if necessary.
                if (treeWalk.isSubtree()) {
                    if (matches) {
                        // Add the directory itself to the result set if its path matches the pattern.
                        result.put(path, Entry.ofDirectory(normalizedRevision, path));
                    }

                    treeWalk.enterSubtree();
                    continue;
                }

                if (!matches) {
                    continue;
                }

                final boolean fetchContent = FindOption.FETCH_CONTENT.get(options);
                // Build an entry as requested.
                final Entry<?> entry;
                final EntryType entryType = EntryType.guessFromPath(path);
                if (fetchContent) {
                    final byte[] content = reader.open(treeWalk.getObjectId(0)).getBytes();
                    switch (entryType) {
                        case JSON:
                            final JsonNode jsonNode = Jackson.readTree(content);
                            entry = Entry.ofJson(normalizedRevision, path, jsonNode);
                            break;
                        case TEXT:
                            final String strVal = sanitizeText(new String(content, UTF_8));
                            entry = Entry.ofText(normalizedRevision, path, strVal);
                            break;
                        default:
                            throw new Error("unexpected entry type: " + entryType);
                    }
                } else {
                    switch (entryType) {
                        case JSON:
                            entry = Entry.ofJson(normalizedRevision, path, Jackson.nullNode);
                            break;
                        case TEXT:
                            entry = Entry.ofText(normalizedRevision, path, "");
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
                    "failed to get data from '" + project.name() + '/' + originalRepoName + "' at " +
                    pathPattern + " for " + normalizedRevision, e);
        }
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

    List<DiffEntry> diff(RevisionRange range, @Nullable RepositoryCache cache) {
        return diff0(range, TreeFilter.ALL, cache);
    }

    Map<String, Change<?>> diff(RevisionRange range, String pathPattern) {
        // Note that we do not cache here because CachingRepository caches the final result.
        return toChangeMap(diff0(range, filter(pathPattern), null));
    }

    private List<DiffEntry> diff0(RevisionRange range, TreeFilter filter, @Nullable RepositoryCache cache) {
        try (RevWalk rw = newRevWalk(jGitRepository)) {
            final RevTree treeA = rw.parseTree(commitIdDatabase.get(range.from()));
            final RevTree treeB = rw.parseTree(commitIdDatabase.get(range.to()));
            return blockingCompareTrees(treeA, treeB, filter, cache);
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to parse two trees: from= " + range.from() +
                                       ", to= " + range.to(), e);
        }
    }

    private List<DiffEntry> blockingCompareTrees(@Nullable RevTree treeA, @Nullable RevTree treeB,
                                                 TreeFilter filter, @Nullable RepositoryCache cache) {
        if (cache == null) {
            return blockingCompareTreesUncached(treeA, treeB, filter);
        }
        final CacheableCompareTreesCall key =
                new CacheableCompareTreesCall(project.repos().get(originalRepoName), treeA, treeB);
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

            newDiffEntries = blockingCompareTreesUncached(treeA, treeB, filter);
            cache.put(key, newDiffEntries);
        } finally {
            lock.unlock();
        }

        logger.debug("Cache miss: {}", key);
        return newDiffEntries;
    }

    private List<DiffEntry> blockingCompareTreesUncached(@Nullable RevTree treeA, @Nullable RevTree treeB,
                                                         TreeFilter filter) {
        try (DiffFormatter diffFormatter = new DiffFormatter(null)) {
            diffFormatter.setRepository(jGitRepository);
            diffFormatter.setPathFilter(filter);
            return ImmutableList.copyOf(diffFormatter.scan(treeA, treeB));
        } catch (IOException e) {
            throw new StorageException("failed to compare two trees: " + treeA + " vs. " + treeB, e);
        }
    }

    private static TreeFilter filter(@Nullable String pathPattern) {
        if (pathPattern == null) {
            return TreeFilter.ALL;
        }

        final PathPatternFilter pathPatternFilter = PathPatternFilter.of(pathPattern);
        return pathPatternFilter.matchesAll() ? TreeFilter.ALL : pathPatternFilter;
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

    Map<String, Change<?>> previewDiff(Revision baseRevision, Iterable<Change<?>> changes) {
        try (ObjectReader reader = jGitRepository.newObjectReader();
             RevWalk revWalk = newRevWalk(reader);
             DiffFormatter diffFormatter = new DiffFormatter(null)) {
            final ObjectId baseTreeId = toTree(commitIdDatabase, revWalk, baseRevision);
            final DirCache dirCache = DirCache.newInCore();
            final int numEdits = applyChanges(baseRevision, baseTreeId, dirCache, changes);
            if (numEdits == 0) {
                return Collections.emptyMap();
            }

            final CanonicalTreeParser p = new CanonicalTreeParser();
            p.reset(reader, baseTreeId);
            diffFormatter.setRepository(jGitRepository);
            final List<DiffEntry> result = diffFormatter.scan(p, new DirCacheIterator(dirCache));
            return toChangeMap(result);
        } catch (IOException e) {
            throw new StorageException("failed to perform a dry-run diff", e);
        }
    }

    private static RevTree toTree(CommitIdDatabase commitIdDatabase, RevWalk revWalk, Revision revision) {
        final ObjectId commitId = commitIdDatabase.get(revision);
        try {
            return revWalk.parseTree(commitId);
        } catch (IOException e) {
            throw new StorageException("failed to parse a commit: " + commitId, e);
        }
    }

    private int applyChanges(@Nullable Revision baseRevision, @Nullable ObjectId baseTreeId,
                             DirCache dirCache, Iterable<Change<?>> changes) {
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
                        final JsonNode newJsonNode = firstNonNull((JsonNode) change.content(),
                                                                  JsonNodeFactory.instance.nullNode());

                        // Upsert only when the contents are really different.
                        if (!Objects.equals(newJsonNode, oldJsonNode)) {
                            applyPathEdit(dirCache, new InsertJson(changePath, inserter, newJsonNode));
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
                            applyPathEdit(dirCache, new InsertText(changePath, inserter, sanitizedNewText));
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
                        final String newPath =
                                ((String) change.content()).substring(1); // Strip the leading '/'.

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
                            editor.add(new CopyOldEntry(newPath, oldEntry));
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
                            applyPathEdit(dirCache, new InsertJson(changePath, inserter, newJsonNode));
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
                            applyPathEdit(dirCache, new InsertText(changePath, inserter, newText));
                            numEdits++;
                        }
                        break;
                }
            }
        } catch (CentralDogmaException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to apply changes on revision " + baseRevision, e);
        }
        return numEdits;
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
                                               String oldDir, @Nullable String newDir, Change<?> change) {

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

        loop:
        for (int i = 0; i < numEntries; i++) {
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
            editor.add(new CopyOldEntry(newPath, e));
        }

        if (editor != null) {
            editor.finish();
            return true;
        } else {
            return false;
        }
    }

    private static void reportNonExistentEntry(Change<?> change) {
        throw new ChangeConflictException("non-existent file/directory: " + change);
    }

    RevisionAndEntries commit(@Nullable Revision prevRevision, Revision nextRevision,
                              long commitTimeMillis, Author author, String summary, String detail,
                              Markup markup, Iterable<Change<?>> changes, boolean allowEmpty) {
        requireNonNull(author, "author");
        requireNonNull(summary, "summary");
        requireNonNull(changes, "changes");
        requireNonNull(detail, "detail");
        requireNonNull(markup, "markup");

        assert prevRevision == null || prevRevision.major() > 0;
        assert nextRevision.major() > 0;

        try (ObjectInserter inserter = jGitRepository.newObjectInserter();
             ObjectReader reader = jGitRepository.newObjectReader();
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
            final int numEdits = applyChanges(prevRevision, prevTreeId, dirCache, changes);

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
                diffFormatter.setRepository(jGitRepository);
                diffEntries = diffFormatter.scan(p, new DirCacheIterator(dirCache));
                isEmpty = diffEntries.isEmpty();
            }

            if (!allowEmpty && isEmpty) {
                throw new RedundantChangeException(
                        "changes did not change anything in " +
                        project.name() + '/' + originalRepoName +
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
            doRefUpdate(jGitRepository, revWalk, R_HEADS_MASTER, nextCommitId);

            return new RevisionAndEntries(nextRevision, diffEntries);
        } catch (CentralDogmaException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to push at '" + project.name() + '/' + originalRepoName + '\'',
                                       e);
        }
    }

    @VisibleForTesting
    static void doRefUpdate(Repository jGitRepo, RevWalk revWalk,
                            String ref, ObjectId commitId) throws IOException {
        if (ref.startsWith(Constants.R_TAGS)) {
            final Ref oldRef = jGitRepo.exactRef(ref);
            if (oldRef != null) {
                throw new StorageException("tag ref exists already: " + ref);
            }
        }

        final RefUpdate refUpdate = jGitRepo.updateRef(ref);
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

    void close() {
        try {
            commitIdDatabase.close();
        } catch (Throwable t) {
            logger.warn("Failed to close a commitId database:", t);
        }
        closeJGitRepo(jGitRepository);
    }

    static final class RevisionAndEntries {
        final Revision revision;
        final List<DiffEntry> diffEntries;

        RevisionAndEntries(Revision revision, List<DiffEntry> diffEntries) {
            this.revision = revision;
            this.diffEntries = diffEntries;
        }
    }

    // PathEdit implementations which is used when applying changes.

    private static final class InsertText extends PathEdit {
        private final ObjectInserter inserter;
        private final String text;

        InsertText(String entryPath, ObjectInserter inserter, String text) {
            super(entryPath);
            this.inserter = inserter;
            this.text = text;
        }

        @Override
        public void apply(DirCacheEntry ent) {
            try {
                ent.setObjectId(inserter.insert(Constants.OBJ_BLOB, text.getBytes(UTF_8)));
                ent.setFileMode(FileMode.REGULAR_FILE);
            } catch (IOException e) {
                throw new StorageException("failed to create a new text blob", e);
            }
        }
    }

    private static final class InsertJson extends PathEdit {
        private final ObjectInserter inserter;
        private final JsonNode jsonNode;

        InsertJson(String entryPath, ObjectInserter inserter, JsonNode jsonNode) {
            super(entryPath);
            this.inserter = inserter;
            this.jsonNode = jsonNode;
        }

        @Override
        public void apply(DirCacheEntry ent) {
            try {
                ent.setObjectId(inserter.insert(Constants.OBJ_BLOB, Jackson.writeValueAsBytes(jsonNode)));
                ent.setFileMode(FileMode.REGULAR_FILE);
            } catch (IOException e) {
                throw new StorageException("failed to create a new JSON blob", e);
            }
        }
    }

    private static final class CopyOldEntry extends PathEdit {
        private final DirCacheEntry oldEntry;

        CopyOldEntry(String entryPath, DirCacheEntry oldEntry) {
            super(entryPath);
            this.oldEntry = oldEntry;
        }

        @Override
        public void apply(DirCacheEntry ent) {
            ent.setFileMode(oldEntry.getFileMode());
            ent.setObjectId(oldEntry.getObjectId());
        }
    }
}
