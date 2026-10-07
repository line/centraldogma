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

import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ALL_WITHOUT_CONTENT;
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ALL_WITH_CONTENT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.OBJECT_ID_ABBREV_STRING_LENGTH;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
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

    // Fetch one extra commit to include the previous head or detect an oversized initial history.
    private static final int MAX_REPLAY_COMMITS = 100;

    private static final String HEAD_REF_MASTER = Constants.R_HEADS + Constants.MASTER;

    AbstractGitMirror(String id, boolean enabled, @Nullable Cron schedule, MirrorDirection direction,
                      Credential credential, Repository localRepo, String localPath,
                      RepositoryUri remoteUri, @Nullable String gitignore, @Nullable String zone,
                      boolean preserveRemoteCommitHistory, boolean publishRemoteCommitTags) {
        super(id, enabled, schedule, direction, credential, localRepo, localPath, remoteUri, gitignore, zone,
              preserveRemoteCommitHistory, publishRemoteCommitTags);
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
                final String configHash = hashString();
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
        final Ref headBranchRef;
        final ObjectId headCommitId;
        final MirrorState oldMirrorState;
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
                                                         preserveRemoteCommitHistory() ?
                                                         MAX_REPLAY_COMMITS + 1 : 2);
        } catch (Exception e) {
            String message = "Failed to fetch the remote repository '" + git.remoteUri() +
                             "' to the local repository '" + localPath() + "'.";
            if (e.getMessage() != null) {
                message += " (reason: " + e.getMessage() + ')';
            }
            throw new GitMirrorException(message, e);
        }

        final UpstreamCommitPlan plan = readUpstreamCommits(git, oldMirrorState, headCommitId);
        final CommittedUpstreamCommits committed = plan.applyUpstreamCommits(
                executor, mirrorStatePath, localRev,
                mirrorDecision == MirrorDecision.COMPARE_AND_RUN, maxNumFiles, maxNumBytes);
        final Revision revision = committed.lastRevision();
        if (revision == null) {
            return newMirrorResultForUpToDate(headBranchRef, triggeredTime);
        }

        final String description;
        if (plan.snapshot()) {
            description = plan.commits().get(0).summary() + ", revision: " + revision.text();
        } else {
            description = "Mirror " + committed.count() + " commit(s) of '" + remoteUri() +
                          "' to the repository '" + localRepo().name() + "', revision: " + revision.text();
        }
        return newMirrorResult(MirrorStatus.SUCCESS, description, triggeredTime);
    }

    private UpstreamCommitPlan readUpstreamCommits(
            GitWithAuth git, @Nullable MirrorState oldMirrorState, ObjectId headCommitId) throws IOException {
        if (preserveRemoteCommitHistory()) {
            final ImmutableList<RevCommit> commitsToReplay =
                    commitsToReplay(git, oldMirrorState, headCommitId);
            if (commitsToReplay != null) {
                final ImmutableList.Builder<UpstreamCommit> upstreamCommits =
                        ImmutableList.builderWithExpectedSize(commitsToReplay.size());
                for (RevCommit commit : commitsToReplay) {
                    upstreamCommits.add(new UpstreamCommit(commit.copy(), upstreamAuthor(commit),
                                                           commitSummary(commit), commit.getFullMessage()));
                }
                return new UpstreamCommitPlan(git, upstreamCommits.build(), false);
            }
        }

        try (ObjectReader reader = git.getRepository().newObjectReader();
             RevWalk revWalk = new RevWalk(reader)) {
            final RevCommit headCommit = revWalk.parseCommit(headCommitId);
            final String summary = "Mirror " + reader.abbreviate(headCommitId).name() + ", '" + remoteUri() +
                                   "' to the repository '" + localRepo().name() + '\'';
            return new UpstreamCommitPlan(
                    git,
                    ImmutableList.of(new UpstreamCommit(
                            headCommit.copy(), MIRROR_AUTHOR, summary,
                            generateCommitDetail(headCommit))), true);
        }
    }

    /**
     * Returns the remote commits to replay, oldest first, or {@code null} if this run has to fall back to
     * pushing a single snapshot of the remote head.
     */
    @Nullable
    private ImmutableList<RevCommit> commitsToReplay(GitWithAuth git, @Nullable MirrorState oldMirrorState,
                                                     ObjectId headCommitId) {
        @Nullable ObjectId previousCommitId = null;
        if (oldMirrorState != null) {
            final String remoteRevision = oldMirrorState.remoteRevision();
            if (remoteRevision == null) {
                return null;
            }
            try {
                previousCommitId = ObjectId.fromString(remoteRevision);
            } catch (IllegalArgumentException e) {
                logger.debug("Not a commit ID: {}", remoteRevision, e);
                return null;
            }
        }

        try (RevWalk revWalk = new RevWalk(git.getRepository())) {
            final RevCommit headCommit = revWalk.parseCommit(headCommitId);
            @Nullable RevCommit previousCommit = null;
            if (previousCommitId != null) {
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
            }

            revWalk.reset();
            revWalk.sort(RevSort.TOPO);
            revWalk.sort(RevSort.REVERSE, true);
            revWalk.markStart(headCommit);
            if (previousCommit != null) {
                revWalk.markUninteresting(previousCommit);
            }
            final ImmutableList.Builder<RevCommit> commits = ImmutableList.builder();
            int numCommits = 0;
            RevCommit expectedParent = previousCommit;
            for (RevCommit commit : revWalk) {
                if (++numCommits > MAX_REPLAY_COMMITS) {
                    logger.info("More than {} commits are reachable from the remote head. " +
                                "Falling back to a single snapshot.", MAX_REPLAY_COMMITS);
                    return null;
                }
                final int expectedParentCount = expectedParent == null ? 0 : 1;
                final boolean hasUnexpectedParent =
                        expectedParent != null && !commit.getParent(0).equals(expectedParent);
                if (commit.getParentCount() != expectedParentCount || hasUnexpectedParent) {
                    logger.info("Remote history is not linear. Falling back to a single snapshot.");
                    return null;
                }
                commits.add(commit);
                expectedParent = commit;
            }
            final ImmutableList<RevCommit> commitsToReplay = commits.build();
            return commitsToReplay.isEmpty() ? null : commitsToReplay;
        } catch (IOException e) {
            logger.warn("Failed to resolve the commits to replay from '{}'. " +
                        "Falling back to mirroring the remote head as a single revision.",
                        git.remoteUri(), e);
            return null;
        }
    }

    @Nullable
    private Revision commitUpstreamCommit(
            CommandExecutor executor, UpstreamCommit upstreamCommit,
            Map<String, Change<?>> remoteChanges, String mirrorStatePath,
            Revision localRev, boolean compareContents) throws IOException {
        final Map<String, Change<?>> changes = new HashMap<>(remoteChanges);
        final String sourceRevision = upstreamCommit.id().name();
        final MirrorState newMirrorState = new MirrorState(sourceRevision, sourceRevision, localRev.text(),
                                                           MirrorDirection.REMOTE_TO_LOCAL, hashString());
        changes.put(mirrorStatePath, Change.ofJsonUpsert(mirrorStatePath,
                                                         Jackson.valueToTree(newMirrorState)));
        final Map<FindOption<?>, ?> findOptions =
                compareContents ? FIND_ALL_WITH_CONTENT : FIND_ALL_WITHOUT_CONTENT;
        final Map<String, Entry<?>> oldEntries =
                localRepo().find(localRev, localPath() + "**", findOptions).join();
        if (compareContents && !hasChanges(changes, oldEntries)) {
            return null;
        }

        oldEntries.keySet().removeAll(changes.keySet());
        oldEntries.forEach((path, entry) -> {
            if (entry.type() != EntryType.DIRECTORY && !changes.containsKey(path)) {
                changes.put(path, Change.ofRemoval(path));
            }
        });
        validateChanges(changes);

        logger.info(upstreamCommit.summary());
        final String upstreamCommitId = upstreamCommitIdToPublish(upstreamCommit.id());
        try {
            return executor.execute(Command.push(
                    null, upstreamCommit.author(), localRepo().parent().name(), localRepo().name(),
                    localRev, upstreamCommit.summary(), upstreamCommit.detail(), Markup.PLAINTEXT,
                    upstreamCommitId, publishRemoteCommitTags() && upstreamCommitId != null,
                    changes.values())).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RedundantChangeException) {
                return null;
            }
            throw e;
        }
    }

    @Nullable
    private String upstreamCommitIdToPublish(ObjectId commitId) {
        if (!preserveRemoteCommitHistory() || !publishRemoteCommitTags()) {
            return null;
        }
        return commitId.name();
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
            GitWithAuth git, ObjectId commitId, int maxNumFiles, long maxNumBytes) throws IOException {
        final Map<String, Change<?>> changes = new HashMap<>();
        try (ObjectReader reader = git.getRepository().newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = new RevWalk(reader)) {

            // Prepare to traverse the tree.
            treeWalk.addTree(revWalk.parseTree(commitId).getId());

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

    private final class UpstreamCommitPlan {
        private final GitWithAuth git;
        private final ImmutableList<UpstreamCommit> commits;
        private final boolean snapshot;

        UpstreamCommitPlan(GitWithAuth git, ImmutableList<UpstreamCommit> commits, boolean snapshot) {
            this.git = git;
            this.commits = commits;
            this.snapshot = snapshot;
        }

        ImmutableList<UpstreamCommit> commits() {
            return commits;
        }

        boolean snapshot() {
            return snapshot;
        }

        CommittedUpstreamCommits applyUpstreamCommits(
                CommandExecutor executor, String mirrorStatePath, Revision localRev, boolean compareContents,
                int maxNumFiles, long maxNumBytes) throws IOException {
            Revision revision = null;
            int committed = 0;
            for (UpstreamCommit upstreamCommit : commits) {
                if (!snapshot) {
                    localRev = localRepo().normalizeNow(Revision.HEAD);
                }
                final Map<String, Change<?>> remoteChanges =
                        collectRemoteChanges(git, upstreamCommit.id(), maxNumFiles, maxNumBytes);
                final Revision committedRevision =
                        commitUpstreamCommit(executor, upstreamCommit, remoteChanges, mirrorStatePath,
                                             localRev, snapshot && compareContents);
                if (committedRevision == null) {
                    continue;
                }
                localRev = committedRevision;
                revision = committedRevision;
                committed++;
            }
            return new CommittedUpstreamCommits(revision, committed);
        }
    }

    private static final class CommittedUpstreamCommits {
        @Nullable
        private final Revision lastRevision;
        private final int count;

        CommittedUpstreamCommits(@Nullable Revision lastRevision, int count) {
            this.lastRevision = lastRevision;
            this.count = count;
        }

        @Nullable
        Revision lastRevision() {
            return lastRevision;
        }

        int count() {
            return count;
        }
    }

    private static final class UpstreamCommit {
        private final ObjectId id;
        private final Author author;
        private final String summary;
        private final String detail;

        UpstreamCommit(ObjectId id, Author author, String summary, String detail) {
            this.id = id;
            this.author = author;
            this.summary = summary;
            this.detail = detail;
        }

        ObjectId id() {
            return id;
        }

        Author author() {
            return author;
        }

        String summary() {
            return summary;
        }

        String detail() {
            return detail;
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
