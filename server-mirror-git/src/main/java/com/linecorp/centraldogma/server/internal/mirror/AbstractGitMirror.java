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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.internal.HistoryConstants.UPSTREAM_TAG_PREFIX;
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ALL_WITHOUT_CONTENT;
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ALL_WITH_CONTENT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.OBJECT_ID_ABBREV_STRING_LENGTH;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.RemoteSetUrlCommand;
import org.eclipse.jgit.api.RemoteSetUrlCommand.UriType;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEditor.DeletePath;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.ignore.IgnoreNode.MatchResult;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.Cron;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MirrorException;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorStatus;
import com.linecorp.centraldogma.server.mirror.RepositoryUri;
import com.linecorp.centraldogma.server.mirror.git.GitMirrorException;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.repository.FindOption;
import com.linecorp.centraldogma.server.storage.repository.Repository;

abstract class AbstractGitMirror extends AbstractMirror {

    private static final Logger logger = LoggerFactory.getLogger(AbstractGitMirror.class);

    private static final Pattern CR = Pattern.compile("\r", Pattern.LITERAL);

    private static final byte[] EMPTY_BYTE = new byte[0];

    private static final int GIT_TIMEOUT_SECS = 60;

    private static final String HEAD_REF_MASTER = Constants.R_HEADS + Constants.MASTER;

    AbstractGitMirror(String id, boolean enabled, @Nullable Cron schedule, MirrorDirection direction,
                      Credential credential, Repository localRepo, String localPath,
                      RepositoryUri remoteUri, @Nullable String gitignore, @Nullable String zone,
                      boolean preserveRemoteCommitHistory) {
        super(id, enabled, schedule, direction, credential, localRepo, localPath, remoteUri, gitignore, zone,
              preserveRemoteCommitHistory);
    }

    GitWithAuth openGit(File workDir,
                        URIish remoteUri,
                        Consumer<TransportCommand<?, ?>> configurator) throws IOException, GitAPIException {
        // Create a unique directory name to avoid conflicts with other mirroring tasks.
        final String dirName = localRepo().parent().name() + '-' + localRepo().name() + '-' + id();
        // Now create and open the repository.
        final File repoDir = new File(workDir, dirName);
        final GitWithAuth git = new GitWithAuth(this, repoDir, remoteUri, configurator);
        boolean success = false;
        try {
            // Set the remote URLs.
            final RemoteSetUrlCommand remoteSetUrl = git.remoteSetUrl();
            remoteSetUrl.setRemoteName(Constants.DEFAULT_REMOTE_NAME);
            remoteSetUrl.setRemoteUri(remoteUri);

            remoteSetUrl.setUriType(UriType.FETCH);
            remoteSetUrl.call();

            remoteSetUrl.setUriType(UriType.PUSH);
            remoteSetUrl.call();

            // XXX(trustin): Do not garbage-collect a Git repository while the server is serving the clients
            //               because GC can incur large amount of disk writes that slow the server.
            //               Ideally, we could introduce some sort of maintenance mode.
            // Keep things clean.
            //git.gc().call();

            success = true;
            return git;
        } finally {
            if (!success) {
                git.close();
            }
        }
    }

    private MirrorDecision shouldRunRemoteToLocal(@Nullable MirrorState oldMirrorState,
                                                  Revision previousLocalHead,
                                                  ObjectId remoteCommitId) {
        if (oldMirrorState == null) {
            // There's no previous mirror state.
            return MirrorDecision.RUN;
        }
        // Run the mirroring if configurations are changed.
        if (!hashString().equals(oldMirrorState.configHash())) {
            return MirrorDecision.RUN;
        }
        if (oldMirrorState.remoteRevision() == null || oldMirrorState.localRevision() == null) {
            // Run the mirror to update the legacy mirror state file.
            return MirrorDecision.RUN;
        }

        if (!remoteCommitId.name().equals(oldMirrorState.remoteRevision())) {
            // The remote (source) repository has commits that are not present locally.
            return MirrorDecision.RUN;
        }

        if (!previousLocalHead.text().equals(oldMirrorState.localRevision())) {
            // Something changed in the mirrored local repository since the last mirroring.
            final String localPath = localPath();
            if ("/".equals(localPath)) {
                return MirrorDecision.RUN;
            }

            // If the local path is not the root, check whether there are changes under the local path only.
            return MirrorDecision.COMPARE_AND_RUN;
        }

        return MirrorDecision.SKIP;
    }

    private MirrorDecision shouldRunLocalToRemote(@Nullable MirrorState oldMirrorState, Revision localHead,
                                                  @Nullable ObjectId previousRemoteCommitId)
            throws IOException {
        if (oldMirrorState == null) {
            // There's no previous mirror state.
            return MirrorDecision.RUN;
        }
        // Run the mirroring if configurations are changed.
        if (!hashString().equals(oldMirrorState.configHash())) {
            return MirrorDecision.RUN;
        }
        if (oldMirrorState.remoteRevision() == null || oldMirrorState.localRevision() == null) {
            // Run the mirror to update the legacy mirror state file.
            return MirrorDecision.RUN;
        }

        if (!localHead.text().equals(oldMirrorState.localRevision())) {
            // The local (source) repository has commits that are not present remotely.
            return MirrorDecision.RUN;
        }

        if (previousRemoteCommitId == null) {
            return MirrorDecision.COMPARE_AND_RUN;
        }

        if (!previousRemoteCommitId.name().equals(oldMirrorState.remoteRevision())) {
            // Something changed in the mirrored remote repository since the last mirroring.
            final String remotePath = remotePath();
            if ("/".equals(remotePath)) {
                return MirrorDecision.RUN;
            }

            // If the remote path is not the root, check whether there are changes under the remote path only.
            return MirrorDecision.COMPARE_AND_RUN;
        }

        return MirrorDecision.SKIP;
    }

    MirrorResult mirrorLocalToRemote(
            GitWithAuth git, int maxNumFiles, long maxNumBytes, Instant triggeredTime)
            throws GitAPIException, IOException {
        // TODO(minwoox): Early return if the remote does not have any updates.
        final Ref headBranchRef = getHeadBranchRef(git);
        final String headBranchRefName = headBranchRef.getName();
        // The head commit and its parent are needed to run the mirroring.
        final ObjectId headCommitId = fetchRemoteHeadAndGetCommitId(git, headBranchRefName, 2);

        final org.eclipse.jgit.lib.Repository gitRepository = git.getRepository();
        final String description;
        try (ObjectReader reader = gitRepository.newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = new RevWalk(reader)) {

            final RevCommit headCommit = revWalk.parseCommit(headCommitId);
            ObjectId previousCommitId = null;
            if (headCommit.getParentCount() > 0) {
                final RevCommit previousCommit = headCommit.getParent(0);
                revWalk.parseHeaders(previousCommit);
                previousCommitId = previousCommit.getId();
            }

            // Prepare to traverse the tree. We can get the tree ID by parsing the object ID.
            final ObjectId headTreeId = revWalk.parseTree(headCommitId).getId();
            treeWalk.reset(headTreeId);

            final String mirrorStatePath = remotePath() + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME;
            final Revision localHead = localRepo().normalizeNow(Revision.HEAD);
            final MirrorState oldMirrorState = remoteCurrentMirrorState(reader, treeWalk, mirrorStatePath);

            // Use previousCommitId because a mirroring task itself will create a new commit.
            final MirrorDecision mirrorDecision = shouldRunLocalToRemote(oldMirrorState, localHead,
                                                                         previousCommitId);
            if (mirrorDecision == MirrorDecision.SKIP) {
                // The remote repository is up-to date.
                description = String.format(
                        "The remote repository '%s' already at %s. Local repository: '%s/%s'",
                        remoteUri(), localHead, localRepo().parent().name(), localRepo().name());
                logger.debug(description);
                return newMirrorResult(MirrorStatus.UP_TO_DATE, description, triggeredTime);
            }

            // Reset to traverse the tree from the first.
            treeWalk.reset(headTreeId);

            // The staging area that keeps the entries of the new tree.
            // It starts with the entries of the tree at the current head and then this method will apply
            // the requested changes to build the new tree.
            final DirCache dirCache = DirCache.newInCore();
            final DirCacheBuilder builder = dirCache.builder();
            builder.addTree(EMPTY_BYTE, 0, reader, headTreeId);
            builder.finish();

            try (ObjectInserter inserter = gitRepository.newObjectInserter()) {
                final boolean hasChanges = addModifiedEntryToCache(localHead, dirCache, reader, inserter,
                                                                   treeWalk, maxNumFiles, maxNumBytes);
                if (mirrorDecision == MirrorDecision.COMPARE_AND_RUN && !hasChanges) {
                    description = String.format(
                            "The remote repository '%s' already at %s. Local repository: '%s/%s'",
                            remoteUri(), localHead, localRepo().parent().name(), localRepo().name());
                    logger.debug(description);
                    return newMirrorResult(MirrorStatus.UP_TO_DATE, description, triggeredTime);
                }

                // Add the mirror state file.
                final String configHash = Hashing.sha256().hashString(toString(), UTF_8).toString();
                final MirrorState newMirrorState = new MirrorState(localHead.text(),
                                                                   headCommitId.name(),
                                                                   localHead.text(),
                                                                   MirrorDirection.LOCAL_TO_REMOTE,
                                                                   configHash);
                applyPathEdit(
                        dirCache, new InsertText(mirrorStatePath.substring(1), // Strip the leading '/'.
                                                 inserter,
                                                 Jackson.writeValueAsPrettyString(newMirrorState) + '\n'));
            }

            final String summary = "Mirror '" + localRepo().name() + "' at " + localHead +
                                   " to the repository '" + remoteUri() + "'\n";
            description = summary;
            final ObjectId nextCommitId =
                    commit(gitRepository, dirCache, headCommitId, summary);
            logger.info(summary);
            updateRef(gitRepository, revWalk, headBranchRefName, nextCommitId);
        }

        git.push()
           .setRefSpecs(new RefSpec(headBranchRefName))
           .setAtomic(true)
           .setTimeout(GIT_TIMEOUT_SECS)
           .call();
        return newMirrorResult(MirrorStatus.SUCCESS, description, triggeredTime);
    }

    MirrorResult mirrorRemoteToLocal(
            GitWithAuth git, CommandExecutor executor, int maxNumFiles, long maxNumBytes, Instant triggeredTime)
            throws Exception {
        final String summary;
        final String detail;
        final Ref headBranchRef;
        final ObjectId headCommitId;
        final MirrorState oldMirrorState;
        final Map<String, Change<?>> changes = new HashMap<>();
        final Revision localRev = localRepo().normalizeNow(Revision.HEAD);
        final String mirrorStatePath = localPath() + MIRROR_STATE_FILE_NAME;
        final MirrorDecision mirrorDecision;
        try {
            headBranchRef = getHeadBranchRef(git);
            oldMirrorState = localCurrentMirrorState(mirrorStatePath, localRev);

            // Decide with the advertised commit ID so that an up-to-date repository does not fetch objects.
            mirrorDecision = shouldRunRemoteToLocal(oldMirrorState, localRev.backward(1),
                                                    headBranchRef.getObjectId());
            if (mirrorDecision == MirrorDecision.SKIP) {
                return newMirrorResultForUpToDate(headBranchRef, triggeredTime);
            }

            // Update the head commit ID again because there's a chance a commit is pushed between the
            // getHeadBranchRef and fetchRemoteHeadAndGetCommitId calls.
            headCommitId = fetchRemoteHeadAndGetCommitId(git, headBranchRef.getName(),
                                                         fetchDepth(oldMirrorState));
        } catch (Exception e) {
            String message = "Failed to fetch the remote repository '" + git.remoteUri() +
                             "' to the local repository '" + localPath() + "'.";
            if (e.getMessage() != null) {
                message += " (reason: " + e.getMessage();
            }
            throw new GitMirrorException(message, e);
        }

        if (preserveRemoteCommitHistory()) {
            final List<RevCommit> replaySet = replaySet(git, oldMirrorState, headCommitId);
            if (replaySet != null) {
                return replayRemoteCommits(git, executor, replaySet, headBranchRef, mirrorStatePath,
                                           maxNumFiles, maxNumBytes, triggeredTime);
            }
        }

        try (ObjectReader reader = git.getRepository().newObjectReader();
             RevWalk revWalk = new RevWalk(reader)) {
            summary = "Mirror " + reader.abbreviate(headCommitId).name() + ", '" + remoteUri() +
                      "' to the repository '" + localRepo().name() + '\'';
            detail = generateCommitDetail(revWalk.parseCommit(headCommitId));
        }
        logger.info(summary);
        changes.putAll(collectRemoteChanges(git, headCommitId, mirrorStatePath, localRev,
                                            maxNumFiles, maxNumBytes));

        final Map<FindOption<?>, ?> findOptions =
                mirrorDecision == MirrorDecision.COMPARE_AND_RUN ? FIND_ALL_WITH_CONTENT
                                                                 : FIND_ALL_WITHOUT_CONTENT;
        final Map<String, Entry<?>> oldEntries = localRepo().find(localRev, localPath() + "**", findOptions)
                                                            .join();
        if (mirrorDecision == MirrorDecision.COMPARE_AND_RUN) {
            if (!hasChanges(changes, oldEntries)) {
                return newMirrorResultForUpToDate(headBranchRef, triggeredTime);
            }
        }

        oldEntries.keySet().removeAll(changes.keySet());

        // Add the removed entries.
        oldEntries.forEach((path, entry) -> {
            if (entry.type() != EntryType.DIRECTORY && !changes.containsKey(path)) {
                changes.put(path, Change.ofRemoval(path));
            }
        });

        validateChanges(changes);
        final String upstreamCommitId = upstreamCommitIdToRecord(headCommitId);
        try {
            final Revision revision = executor.execute(Command.push(
                    null, MIRROR_AUTHOR, localRepo().parent().name(), localRepo().name(),
                    localRev, summary, detail, Markup.PLAINTEXT, upstreamCommitId, changes.values())).join();
            final String description = summary + ", revision: " + revision.text();
            return newMirrorResult(MirrorStatus.SUCCESS, description, triggeredTime);
        } catch (CompletionException e) {
            if (e.getCause() instanceof RedundantChangeException) {
                return newMirrorResultForUpToDate(headBranchRef, triggeredTime);
            }
            throw e;
        }
    }

    // Fetch one more generation so a linear history can include the previously mirrored commit.
    private static final int MAX_REPLAY_COMMITS = 100;

    private int fetchDepth(@Nullable MirrorState oldMirrorState) {
        if (!preserveRemoteCommitHistory() || oldMirrorState == null ||
            oldMirrorState.remoteRevision() == null) {
            // First runs take snapshots and need only HEAD and its parent.
            return 2;
        }
        return MAX_REPLAY_COMMITS + 1;
    }

    /**
     * Returns the remote commits to replay, oldest first, or {@code null} if this run has to fall back to
     * pushing a single snapshot of the remote head.
     */
    @Nullable
    private List<RevCommit> replaySet(GitWithAuth git, @Nullable MirrorState oldMirrorState,
                                      ObjectId headCommitId) {
        if (oldMirrorState == null) {
            // The first run for this mirror has no commit to replay from.
            return null;
        }
        final String remoteRevision = oldMirrorState.remoteRevision();
        if (remoteRevision == null) {
            return null;
        }

        final ObjectId previousCommitId;
        try {
            previousCommitId = ObjectId.fromString(remoteRevision);
        } catch (IllegalArgumentException e) {
            logger.debug("Not a commit ID: {}", remoteRevision, e);
            return null;
        }

        try (RevWalk revWalk = new RevWalk(git.getRepository())) {
            final RevCommit headCommit = revWalk.parseCommit(headCommitId);
            final RevCommit previousCommit;
            try {
                previousCommit = revWalk.parseCommit(previousCommitId);
            } catch (MissingObjectException e) {
                // The previously mirrored commit is outside the fetch window, or the remote dropped it.
                return null;
            }
            if (!revWalk.isMergedInto(previousCommit, headCommit)) {
                // A non-fast-forward remote can only be represented by one snapshot at its new HEAD.
                return null;
            }

            revWalk.reset();
            revWalk.sort(RevSort.TOPO);
            revWalk.sort(RevSort.REVERSE, true);
            revWalk.markStart(headCommit);
            revWalk.markUninteresting(previousCommit);
            final ImmutableList.Builder<RevCommit> commits = ImmutableList.builder();
            int numCommits = 0;
            for (RevCommit commit : revWalk) {
                if (++numCommits > MAX_REPLAY_COMMITS) {
                    logger.info("More than {} commits are reachable from the remote head. " +
                                "Falling back to a single snapshot.", MAX_REPLAY_COMMITS);
                    return null;
                }
                commits.add(commit);
            }
            final List<RevCommit> replaySet = commits.build();
            return replaySet.isEmpty() ? null : replaySet;
        } catch (IOException e) {
            logger.warn("Failed to resolve the commits to replay from '{}'. " +
                        "Falling back to mirroring the remote head as a single revision.",
                        git.remoteUri(), e);
            return null;
        }
    }

    private MirrorResult replayRemoteCommits(
            GitWithAuth git, CommandExecutor executor, List<RevCommit> replaySet, Ref headBranchRef,
            String mirrorStatePath, int maxNumFiles, long maxNumBytes, Instant triggeredTime)
            throws IOException {
        Revision revision = null;
        int replayed = 0;
        // The size limits apply to each mirrored tree; MAX_REPLAY_COMMITS bounds the whole run.
        for (RevCommit commit : replaySet) {
            final Revision localRev = localRepo().normalizeNow(Revision.HEAD);
            final Map<String, Change<?>> changes =
                    collectRemoteChanges(git, commit, mirrorStatePath, localRev, maxNumFiles, maxNumBytes);
            final Map<String, Entry<?>> oldEntries =
                    localRepo().find(localRev, localPath() + "**", FIND_ALL_WITHOUT_CONTENT).join();
            oldEntries.keySet().removeAll(changes.keySet());
            oldEntries.forEach((path, entry) -> {
                if (entry.type() != EntryType.DIRECTORY && !changes.containsKey(path)) {
                    changes.put(path, Change.ofRemoval(path));
                }
            });
            validateChanges(changes);

            final String summary = commitSummary(commit);
            logger.info(summary);
            final String upstreamCommitId = upstreamCommitIdToRecord(commit);
            try {
                revision = executor.execute(Command.push(
                        null, upstreamAuthor(commit), localRepo().parent().name(), localRepo().name(),
                        localRev, summary, commit.getFullMessage(), Markup.PLAINTEXT, upstreamCommitId,
                        changes.values())).join();
                replayed++;
            } catch (CompletionException e) {
                if (e.getCause() instanceof RedundantChangeException) {
                    continue;
                }
                throw e;
            }
        }

        if (revision == null) {
            return newMirrorResultForUpToDate(headBranchRef, triggeredTime);
        }
        final String description = "Mirror " + replayed + " commit(s) of '" + remoteUri() +
                                   "' to the repository '" + localRepo().name() + "', revision: " +
                                   revision.text();
        return newMirrorResult(MirrorStatus.SUCCESS, description, triggeredTime);
    }

    @Nullable
    private String upstreamCommitIdToRecord(ObjectId commitId) throws IOException {
        if (!preserveRemoteCommitHistory()) {
            return null;
        }
        final String refName = Constants.R_TAGS + UPSTREAM_TAG_PREFIX + commitId.name();
        return localRepo().jGitRepository().exactRef(refName) == null ? commitId.name() : null;
    }

    private String commitSummary(RevCommit commit) {
        final String shortMessage = commit.getShortMessage();
        if (!shortMessage.isEmpty()) {
            return shortMessage;
        }
        // Git allows an empty commit message, which would leave the history with a blank row.
        return "Mirror " + commit.abbreviate(OBJECT_ID_ABBREV_STRING_LENGTH).name() + ", '" + remoteUri() +
               "' to the repository '" + localRepo().name() + '\'';
    }

    private static Author upstreamAuthor(RevCommit commit) {
        final PersonIdent ident = commit.getAuthorIdent();
        if (ident == null || ident.getName() == null || ident.getEmailAddress() == null) {
            return MIRROR_AUTHOR;
        }
        try {
            return new Author(ident.getName(), ident.getEmailAddress());
        } catch (RuntimeException e) {
            // Git accepts identities that Author does not, such as an empty e-mail address.
            logger.debug("Cannot use the remote commit author of {}: {}", commit.name(), ident, e);
            return MIRROR_AUTHOR;
        }
    }

    private Map<String, Change<?>> collectRemoteChanges(
            GitWithAuth git, ObjectId commitId, String mirrorStatePath, Revision localRev,
            int maxNumFiles, long maxNumBytes) throws IOException {
        final Map<String, Change<?>> changes = new HashMap<>();
        try (ObjectReader reader = git.getRepository().newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = new RevWalk(reader)) {

            // Prepare to traverse the tree.
            treeWalk.addTree(revWalk.parseTree(commitId).getId());

            // Add mirror_state.json.
            final String sourceRevision = commitId.name();
            final MirrorState newMirrorState = new MirrorState(sourceRevision, sourceRevision, localRev.text(),
                                                               MirrorDirection.REMOTE_TO_LOCAL, hashString());
            changes.put(mirrorStatePath, Change.ofJsonUpsert(mirrorStatePath,
                                                             Jackson.valueToTree(newMirrorState)));
            long numFiles = 0;
            long numBytes = 0;
            while (treeWalk.next()) {
                final FileMode fileMode = treeWalk.getFileMode();
                final String path = '/' + treeWalk.getPathString();

                if (ignoreNode() != null && path.startsWith(remotePath())) {
                    if (ignoreNode().isIgnored('/' + path.substring(remotePath().length()),
                                               fileMode == FileMode.TREE) == MatchResult.IGNORED) {
                        continue;
                    }
                }

                if (fileMode == FileMode.TREE) {
                    maybeEnterSubtree(treeWalk, remotePath(), path);
                    continue;
                }

                if (fileMode != FileMode.REGULAR_FILE && fileMode != FileMode.EXECUTABLE_FILE) {
                    // Skip non-file entries.
                    continue;
                }

                // Skip the entries that are not under the remote path.
                if (!path.startsWith(remotePath())) {
                    continue;
                }
                if (path.endsWith("/.gitmodules")) {
                    // Submodules are not supported.
                    continue;
                }

                final String localPath = localPath() + path.substring(remotePath().length());

                // Skip the entry whose path does not conform to CD's path rule.
                if (!Util.isValidFilePath(localPath)) {
                    continue;
                }

                if (++numFiles > maxNumFiles) {
                    throw newMirrorException(maxNumFiles, "files");
                }

                final ObjectId objectId = treeWalk.getObjectId(0);
                final long contentLength = reader.getObjectSize(objectId, ObjectReader.OBJ_ANY);
                if (numBytes > maxNumBytes - contentLength) {
                    throw newMirrorException(maxNumBytes, "bytes");
                }
                numBytes += contentLength;

                final String content = new String(reader.open(objectId).getBytes(), UTF_8);
                switch (EntryType.guessFromPath(localPath)) {
                    case JSON:
                        changes.putIfAbsent(localPath, Change.ofJsonUpsert(localPath, content));
                        break;
                    case YAML:
                        changes.putIfAbsent(localPath, Change.ofYamlUpsert(localPath, content));
                        break;
                    case TEXT:
                        changes.putIfAbsent(localPath, Change.ofTextUpsert(localPath, content));
                        break;
                }
            }
        }
        return changes;
    }

    private static boolean hasChanges(Map<String, Change<?>> newChanges, Map<String, Entry<?>> oldEntries) {
        // Simply check whether there's any addition, removal first.
        for (Change<?> change : newChanges.values()) {
            final String path = change.path();
            if (path.endsWith(MIRROR_STATE_FILE_NAME)) {
                continue;
            }
            final Entry<?> oldEntry = oldEntries.get(path);
            if (oldEntry == null) {
                // New entry added.
                return true;
            }
        }
        for (Entry<?> entry : oldEntries.values()) {
            if (entry.type() == EntryType.DIRECTORY) {
                continue;
            }
            final String path = entry.path();
            if (path.endsWith(MIRROR_STATE_FILE_NAME)) {
                continue;
            }
            final Change<?> change = newChanges.get(path);
            if (change == null) {
                // Entry removed.
                return true;
            }
        }

        for (Change<?> change : newChanges.values()) {
            final String path = change.path();
            if (path.endsWith(MIRROR_STATE_FILE_NAME)) {
                continue;
            }
            final String newContent = sanitizeText(change.rawContent());
            final Entry<?> oldEntry = oldEntries.get(path);
            final String oldContent = oldEntry.rawContent();
            if (!newContent.equals(oldContent)) {
                // Content changed.
                return true;
            }
        }
        return false;
    }

    private MirrorResult newMirrorResultForUpToDate(Ref headBranchRef, Instant triggeredTime) {
        final String abbrId = headBranchRef.getObjectId().abbreviate(OBJECT_ID_ABBREV_STRING_LENGTH).name();
        final String message = String.format("Repository '%s/%s' already at %s, %s#%s",
                                             localRepo().parent().name(), localRepo().name(), abbrId,
                                             remoteRepoUri(), remoteBranch());
        // The local repository is up-to date.
        logger.debug(message);
        return newMirrorResult(MirrorStatus.UP_TO_DATE, message, triggeredTime);
    }

    @Nullable
    private MirrorState localCurrentMirrorState(String mirrorStatePath, Revision localRev)
            throws JsonParseException, JsonMappingException {
        final Entry<?> mirrorStateJson = localRepo().getOrNull(localRev, mirrorStatePath).join();
        if (mirrorStateJson == null || mirrorStateJson.type() != EntryType.JSON) {
            return null;
        } else {
            return Jackson.treeToValue((TreeNode) mirrorStateJson.content(), MirrorState.class);
        }
    }

    private Ref getHeadBranchRef(GitWithAuth git) throws GitAPIException {
        if (!remoteBranch().isEmpty()) {
            final String headBranchRefName = Constants.R_HEADS + remoteBranch();
            final Collection<Ref> refs = lsRemote(git, true);
            return findHeadBranchRef(git, headBranchRefName, refs);
        }

        // Otherwise, we need to figure out which branch we should fetch.
        // Fetch the remote reference list to determine the default branch.
        final Collection<Ref> refs = lsRemote(git, false);

        // Find and resolve 'HEAD' reference, which leads us to the default branch.
        final Optional<String> headRefNameOptional = refs.stream()
                                                         .filter(ref -> Constants.HEAD.equals(ref.getName()))
                                                         .map(ref -> ref.getTarget().getName())
                                                         .findFirst();
        final String headBranchRefName;
        if (headRefNameOptional.isPresent()) {
            headBranchRefName = headRefNameOptional.get();
        } else {
            // We should not reach here, but if we do, fall back to 'refs/heads/master'.
            headBranchRefName = HEAD_REF_MASTER;
        }
        return findHeadBranchRef(git, headBranchRefName, refs);
    }

    private static Collection<Ref> lsRemote(GitWithAuth git,
                                            boolean setHeads) throws GitAPIException {
        return git.lsRemote()
                  .setTags(false)
                  .setTimeout(GIT_TIMEOUT_SECS)
                  .setHeads(setHeads)
                  .call();
    }

    private static Ref findHeadBranchRef(GitWithAuth git, String headBranchRefName, Collection<Ref> refs) {
        final Optional<Ref> headBranchRef = refs.stream()
                                                .filter(ref -> headBranchRefName.equals(ref.getName()))
                                                .findFirst();
        if (headBranchRef.isPresent()) {
            return headBranchRef.get();
        }
        throw new GitMirrorException("Remote does not have " + headBranchRefName + " branch. remote: " +
                                     git.remoteUri());
    }

    private static String generateCommitDetail(RevCommit headCommit) {
        final PersonIdent authorIdent = headCommit.getAuthorIdent();
        return "Remote commit:\n" +
               "- SHA: " + headCommit.name() + '\n' +
               "- Subject: " + headCommit.getShortMessage() + '\n' +
               "- Author: " + authorIdent.getName() + " <" +
               authorIdent.getEmailAddress() + "> \n" +
               "- Date: " + authorIdent.getWhen() + "\n\n" +
               headCommit.getFullMessage();
    }

    @Nullable
    private MirrorState remoteCurrentMirrorState(
            ObjectReader reader, TreeWalk treeWalk, String mirrorStatePath) {
        try {
            while (treeWalk.next()) {
                final FileMode fileMode = treeWalk.getFileMode();
                final String path = '/' + treeWalk.getPathString();

                // Recurse into a directory if necessary.
                if (fileMode == FileMode.TREE) {
                    if (remotePath().startsWith(path + '/')) {
                        treeWalk.enterSubtree();
                    }
                    continue;
                }

                if (!path.equals(mirrorStatePath)) {
                    continue;
                }

                final byte[] content = currentEntryContent(reader, treeWalk);
                return Jackson.readValue(content, MirrorState.class);
            }
            // There's no mirror state file which means this is the first mirroring or the file is removed.
            return null;
        } catch (Exception e) {
            logger.warn("Unexpected exception while retrieving the remote source revision", e);
            return null;
        }
    }

    private static ObjectId fetchRemoteHeadAndGetCommitId(
            GitWithAuth git, String headBranchRefName, int depth) throws GitAPIException, IOException {
        final FetchResult fetchResult = git.fetch()
                                           .setDepth(depth)
                                           .setRefSpecs(new RefSpec(headBranchRefName))
                                           .setRemoveDeletedRefs(true)
                                           .setTagOpt(TagOpt.NO_TAGS)
                                           .setTimeout(GIT_TIMEOUT_SECS)
                                           .call();
        final ObjectId commitId = fetchResult.getAdvertisedRef(headBranchRefName).getObjectId();
        final RefUpdate refUpdate = git.getRepository().updateRef(headBranchRefName);
        refUpdate.setNewObjectId(commitId);
        refUpdate.setForceUpdate(true);
        refUpdate.update();
        return commitId;
    }

    private Map<String, Entry<?>> localHeadEntries(Revision localHead) {
        final Map<String, Entry<?>> localRawHeadEntries = localRepo().find(localHead, localPath() + "**")
                                                                     .join();
        return filterByGitignore(localRawHeadEntries, localPath());
    }

    private boolean addModifiedEntryToCache(Revision localHead, DirCache dirCache, ObjectReader reader,
                                            ObjectInserter inserter, TreeWalk treeWalk,
                                            int maxNumFiles, long maxNumBytes) throws IOException {
        final Map<String, Entry<?>> localHeadEntries = localHeadEntries(localHead);
        long numFiles = 0;
        long numBytes = 0;
        boolean hasChanges = false;
        while (treeWalk.next()) {
            final FileMode fileMode = treeWalk.getFileMode();
            final String pathString = treeWalk.getPathString();
            final String remoteFilePath = '/' + pathString;

            // Recurse into a directory if necessary.
            if (fileMode == FileMode.TREE) {
                maybeEnterSubtree(treeWalk, remotePath(), remoteFilePath);
                continue;
            }

            if (fileMode != FileMode.REGULAR_FILE && fileMode != FileMode.EXECUTABLE_FILE) {
                // Skip non-file entries.
                continue;
            }

            // Skip the entries that are not under the remote path.
            if (!remoteFilePath.startsWith(remotePath())) {
                continue;
            }

            final String localFilePath = localPath() + remoteFilePath.substring(remotePath().length());

            // Skip the entry whose path does not conform to CD's path rule.
            if (!Util.isValidFilePath(localFilePath)) {
                continue;
            }
            if (localFilePath.endsWith(MIRROR_STATE_FILE_NAME)) {
                // Skip the mirror state file as it only exists in the remote repository.
                continue;
            }

            final Entry<?> entry = localHeadEntries.remove(localFilePath);
            if (entry == null) {
                // Remove a deleted entry.
                hasChanges = true;
                applyPathEdit(dirCache, new DeletePath(pathString));
                continue;
            }

            if (++numFiles > maxNumFiles) {
                throw newMirrorException(maxNumFiles, "files");
            }

            final byte[] oldContent = currentEntryContent(reader, treeWalk);
            final long contentLength = applyPathEdit(dirCache, inserter, pathString, entry, oldContent);
            if (contentLength > 0) {
                hasChanges = true;
            }
            numBytes += contentLength;
            if (numBytes > maxNumBytes) {
                throw newMirrorException(maxNumBytes, "bytes");
            }
        }

        // Add newly added entries.
        for (Map.Entry<String, Entry<?>> entry : localHeadEntries.entrySet()) {
            final Entry<?> value = entry.getValue();
            if (value.type() == EntryType.DIRECTORY) {
                continue;
            }
            if (entry.getKey().endsWith(MIRROR_STATE_FILE_NAME)) {
                continue;
            }

            if (++numFiles > maxNumFiles) {
                throw newMirrorException(maxNumFiles, "files");
            }

            final String convertedPath = remotePath().substring(1) + // Strip the leading '/'
                                         entry.getKey().substring(localPath().length());
            final long contentLength = applyPathEdit(dirCache, inserter, convertedPath, value, null);
            if (contentLength > 0) {
                hasChanges = true;
            }
            numBytes += contentLength;
            if (numBytes > maxNumBytes) {
                throw newMirrorException(maxNumBytes, "bytes");
            }
        }
        return hasChanges;
    }

    private static long applyPathEdit(DirCache dirCache, ObjectInserter inserter, String pathString,
                                      Entry<?> entry, byte @Nullable [] oldContent)
            throws JsonProcessingException {
        switch (EntryType.guessFromPath(pathString)) {
            case JSON:
            case YAML:
                final String oldData = oldContent != null ? sanitizeText(new String(oldContent, UTF_8)) : null;
                final String newData = sanitizeText(entry.rawContent());
                assert newData != null;
                // Upsert only when the contents are really different.
                if (!newData.equals(oldData)) {
                    applyPathEdit(dirCache, new InsertText(pathString, inserter, newData));
                    return newData.length();
                }
                break;
            case TEXT:
                final String sanitizedOldText = oldContent != null ?
                                                sanitizeText(new String(oldContent, UTF_8)) : null;
                final String sanitizedNewText = entry.contentAsText(); // Already sanitized when committing.
                // Upsert only when the contents are really different.
                if (!sanitizedNewText.equals(sanitizedOldText)) {
                    applyPathEdit(dirCache, new InsertText(pathString, inserter, sanitizedNewText));
                    return sanitizedNewText.length();
                }
                break;
        }
        return 0;
    }

    private static void applyPathEdit(DirCache dirCache, PathEdit edit) {
        final DirCacheEditor e = dirCache.editor();
        e.add(edit);
        e.finish();
    }

    private static byte[] currentEntryContent(ObjectReader reader, TreeWalk treeWalk) throws IOException {
        final ObjectId objectId = treeWalk.getObjectId(0);
        return reader.open(objectId).getBytes();
    }

    private static void maybeEnterSubtree(
            TreeWalk treeWalk, String remotePath, String path) throws IOException {
        // Enter if the directory is under the remote path.
        // e.g.
        // path == /foo/bar
        // remotePath == /foo/
        if (path.startsWith(remotePath)) {
            treeWalk.enterSubtree();
            return;
        }

        // Enter if the directory is equal to the remote path.
        // e.g.
        // path == /foo
        // remotePath == /foo/
        final int pathLen = path.length() + 1; // Include the trailing '/'.
        if (pathLen == remotePath.length() && remotePath.startsWith(path)) {
            treeWalk.enterSubtree();
            return;
        }

        // Enter if the directory is the parent of the remote path.
        // e.g.
        // path == /foo
        // remotePath == /foo/bar/
        if (pathLen < remotePath.length() && remotePath.startsWith(path + '/')) {
            treeWalk.enterSubtree();
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

    private static ObjectId commit(org.eclipse.jgit.lib.Repository gitRepository, DirCache dirCache,
                                   ObjectId headCommitId, String message) throws IOException {
        try (ObjectInserter inserter = gitRepository.newObjectInserter()) {
            // flush the current index to repository and get the result tree object id.
            final ObjectId nextTreeId = dirCache.writeTree(inserter);
            // build a commit object
            final PersonIdent personIdent =
                    new PersonIdent(MIRROR_AUTHOR.name(), MIRROR_AUTHOR.email(),
                                    System.currentTimeMillis() / 1000L * 1000L, // Drop the milliseconds
                                    0);

            final CommitBuilder commitBuilder = new CommitBuilder();
            commitBuilder.setAuthor(personIdent);
            commitBuilder.setCommitter(personIdent);
            commitBuilder.setTreeId(nextTreeId);
            commitBuilder.setEncoding(UTF_8);
            commitBuilder.setParentId(headCommitId);
            commitBuilder.setMessage(message);

            final ObjectId nextCommitId = inserter.insert(commitBuilder);
            inserter.flush();
            return nextCommitId;
        }
    }

    private MirrorException newMirrorException(long number, String filesOrBytes) {
        return new MirrorException("mirror (" + remoteRepoUri() + '#' + remoteBranch() +
                                   ") contains more than " + number + ' ' + filesOrBytes);
    }

    static void updateRef(org.eclipse.jgit.lib.Repository jGitRepository, RevWalk revWalk,
                          String ref, ObjectId commitId) throws IOException {
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
}
