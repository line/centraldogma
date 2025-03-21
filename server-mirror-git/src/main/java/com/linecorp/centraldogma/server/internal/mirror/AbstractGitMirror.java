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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.OBJECT_ID_ABBREV_STRING_LENGTH;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

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
import org.eclipse.jgit.ignore.IgnoreNode;
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
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.Cron;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

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
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorStatus;
import com.linecorp.centraldogma.server.mirror.git.GitMirrorException;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.repository.Repository;

abstract class AbstractGitMirror extends AbstractMirror {

    private static final Logger logger = LoggerFactory.getLogger(AbstractGitMirror.class);

    // We are going to hide this file from CD UI after we implement UI for mirroring.
    private static final String MIRROR_STATE_FILE_NAME = "mirror_state.json";

    // Prepend '.' because this file is metadata.
    private static final String LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME = '.' + MIRROR_STATE_FILE_NAME;

    private static final Pattern CR = Pattern.compile("\r", Pattern.LITERAL);

    private static final byte[] EMPTY_BYTE = new byte[0];

    private static final Pattern DISALLOWED_CHARS = Pattern.compile("[^-_a-zA-Z]");
    private static final Pattern CONSECUTIVE_UNDERSCORES = Pattern.compile("_+");

    private static final int GIT_TIMEOUT_SECS = 60;

    private static final String HEAD_REF_MASTER = Constants.R_HEADS + Constants.MASTER;

    @Nullable
    private IgnoreNode ignoreNode;

    AbstractGitMirror(String id, boolean enabled, @Nullable Cron schedule, MirrorDirection direction,
                      Credential credential, Repository localRepo, String localPath,
                      URI remoteRepoUri, String remotePath, String remoteBranch,
                      @Nullable String gitignore, @Nullable String zone) {
        super(id, enabled, schedule, direction, credential, localRepo, localPath, remoteRepoUri, remotePath,
              remoteBranch, gitignore, zone);

        if (gitignore != null) {
            ignoreNode = new IgnoreNode();
            try {
                ignoreNode.parse(new ByteArrayInputStream(gitignore.getBytes()));
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read gitignore: " + gitignore, e);
            }
        }
    }

    GitWithAuth openGit(File workDir,
                        URIish remoteUri,
                        Consumer<TransportCommand<?, ?>> configurator) throws IOException, GitAPIException {
        // Now create and open the repository.
        final File repoDir = new File(
                workDir,
                CONSECUTIVE_UNDERSCORES.matcher(DISALLOWED_CHARS.matcher(
                        remoteRepoUri().toASCIIString()).replaceAll("_")).replaceAll("_"));
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

    MirrorResult mirrorLocalToRemote(
            GitWithAuth git, int maxNumFiles, long maxNumBytes, Instant triggeredTime)
            throws GitAPIException, IOException {
        // TODO(minwoox): Early return if the remote does not have any updates.
        final Ref headBranchRef = getHeadBranchRef(git);
        final String headBranchRefName = headBranchRef.getName();
        final ObjectId headCommitId = fetchRemoteHeadAndGetCommitId(git, headBranchRefName);

        final org.eclipse.jgit.lib.Repository gitRepository = git.getRepository();
        final String description;
        try (ObjectReader reader = gitRepository.newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = new RevWalk(reader)) {

            // Prepare to traverse the tree. We can get the tree ID by parsing the object ID.
            final ObjectId headTreeId = revWalk.parseTree(headCommitId).getId();
            treeWalk.reset(headTreeId);

            final String mirrorStatePath = remotePath() + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME;
            final Revision localHead = localRepo().normalizeNow(Revision.HEAD);
            final Revision remoteCurrentRevision = remoteCurrentRevision(reader, treeWalk, mirrorStatePath);
            if (localHead.equals(remoteCurrentRevision)) {
                // The remote repository is up-to date.
                description = String.format(
                        "The remote repository '%s#%s' already at %s. Local repository: '%s/%s'",
                        remoteRepoUri(), remoteBranch(), localHead,
                        localRepo().parent().name(), localRepo().name());
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
                addModifiedEntryToCache(localHead, dirCache, reader, inserter,
                                        treeWalk, maxNumFiles, maxNumBytes);
                // Add the mirror state file.
                final MirrorState mirrorState = new MirrorState(localHead.text());
                applyPathEdit(
                        dirCache, new InsertText(mirrorStatePath.substring(1), // Strip the leading '/'.
                                                 inserter,
                                                 Jackson.writeValueAsPrettyString(mirrorState) + '\n'));
            }

            final String summary = "Mirror '" + localRepo().name() + "' at " + localHead +
                                   " to the repository '" + remoteRepoUri() + '#' + remoteBranch() + "'\n";
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
        final Map<String, Change<?>> changes = new HashMap<>();
        final Revision localRev = localRepo().normalizeNow(Revision.HEAD);
        final String mirrorStatePath = localPath() + MIRROR_STATE_FILE_NAME;
        try {
            headBranchRef = getHeadBranchRef(git);
            if (!needsFetch(headBranchRef, mirrorStatePath, localRev)) {
                return newMirrorResultForUpToDate(headBranchRef, triggeredTime);
            }

            // Update the head commit ID again because there's a chance a commit is pushed between the
            // getHeadBranchRefName and fetchRemoteHeadAndGetCommitId calls.
            headCommitId = fetchRemoteHeadAndGetCommitId(git, headBranchRef.getName());
        } catch (Exception e) {
            String message = "Failed to fetch the remote repository '" + git.remoteUri() +
                             "' to the local repository '" + localPath() + "'.";
            if (e.getMessage() != null) {
                message += " (reason: " + e.getMessage();
            }
            throw new GitMirrorException(message, e);
        }

        try (ObjectReader reader = git.getRepository().newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = new RevWalk(reader)) {

            // Prepare to traverse the tree.
            treeWalk.addTree(revWalk.parseTree(headCommitId).getId());
            final String abbrId = reader.abbreviate(headCommitId).name();

            // Add mirror_state.json.
            changes.put(mirrorStatePath, Change.ofJsonUpsert(
                    mirrorStatePath, "{ \"sourceRevision\": \"" + headCommitId.name() + "\" }"));
            // Construct the log message and log.
            final String branchName = getRemoteBranchName(headBranchRef);
            summary = "Mirror " + abbrId + ", '" + remoteRepoUri() + '#' + branchName +
                      "' to the repository '" + localRepo().name() + '\'';
            final RevCommit headCommit = revWalk.parseCommit(headCommitId);
            detail = generateCommitDetail(headCommit);
            logger.info(summary);
            long numFiles = 0;
            long numBytes = 0;
            while (treeWalk.next()) {
                final FileMode fileMode = treeWalk.getFileMode();
                final String path = '/' + treeWalk.getPathString();

                if (ignoreNode != null && path.startsWith(remotePath())) {
                    assert ignoreNode != null;
                    if (ignoreNode.isIgnored('/' + path.substring(remotePath().length()),
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

                final String localPath = localPath() + path.substring(remotePath().length());

                // Skip the entry whose path does not conform to CD's path rule.
                if (!Util.isValidFilePath(localPath)) {
                    continue;
                }

                if (++numFiles > maxNumFiles) {
                    throwMirrorException(maxNumFiles, "files");
                }

                final ObjectId objectId = treeWalk.getObjectId(0);
                final long contentLength = reader.getObjectSize(objectId, ObjectReader.OBJ_ANY);
                if (numBytes > maxNumBytes - contentLength) {
                    throwMirrorException(maxNumBytes, "bytes");
                }
                numBytes += contentLength;

                final byte[] content = reader.open(objectId).getBytes();
                switch (EntryType.guessFromPath(localPath)) {
                    case JSON:
                        final JsonNode jsonNode = Jackson.readTree(content);
                        changes.putIfAbsent(localPath, Change.ofJsonUpsert(localPath, jsonNode));
                        break;
                    case TEXT:
                        final String strVal = new String(content, UTF_8);
                        changes.putIfAbsent(localPath, Change.ofTextUpsert(localPath, strVal));
                        break;
                }
            }
        }

        final Map<String, Entry<?>> oldEntries = localRepo().find(
                localRev, localPath() + "**", FIND_ALL_WITHOUT_CONTENT).join();
        oldEntries.keySet().removeAll(changes.keySet());

        // Add the removed entries.
        oldEntries.forEach((path, entry) -> {
            if (entry.type() != EntryType.DIRECTORY && !changes.containsKey(path)) {
                changes.put(path, Change.ofRemoval(path));
            }
        });

        try {
            final CommitResult commitResult = executor.execute(Command.push(
                    MIRROR_AUTHOR, localRepo().parent().name(), localRepo().name(),
                    Revision.HEAD, summary, detail, Markup.PLAINTEXT, changes.values())).join();
            final String description = summary + ", revision: " + commitResult.revision().text();
            return newMirrorResult(MirrorStatus.SUCCESS, description, triggeredTime);
        } catch (CompletionException e) {
            if (e.getCause() instanceof RedundantChangeException) {
                return newMirrorResultForUpToDate(headBranchRef, triggeredTime);
            }
            throw e;
        }
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

    private boolean needsFetch(Ref headBranchRef, String mirrorStatePath, Revision localRev)
            throws JsonParseException, JsonMappingException {
        final Entry<?> mirrorState = localRepo().getOrNull(localRev, mirrorStatePath).join();
        final String localSourceRevision;
        if (mirrorState == null || mirrorState.type() != EntryType.JSON) {
            localSourceRevision = null;
        } else {
            localSourceRevision = Jackson.treeToValue((TreeNode) mirrorState.content(),
                                                      MirrorState.class).sourceRevision();
        }

        final ObjectId headCommitId = headBranchRef.getObjectId();
        return !headCommitId.name().equals(localSourceRevision);
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
    private Revision remoteCurrentRevision(
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
                final MirrorState mirrorState = Jackson.readValue(content, MirrorState.class);
                return new Revision(mirrorState.sourceRevision());
            }
            // There's no mirror state file which means this is the first mirroring or the file is removed.
            return null;
        } catch (Exception e) {
            logger.warn("Unexpected exception while retrieving the remote source revision", e);
            return null;
        }
    }

    private static ObjectId fetchRemoteHeadAndGetCommitId(
            GitWithAuth git, String headBranchRefName) throws GitAPIException, IOException {
        final FetchResult fetchResult = git.fetch()
                                           .setDepth(1)
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

        final Stream<Map.Entry<String, Entry<?>>> entryStream =
                localRawHeadEntries.entrySet()
                                   .stream();
        if (ignoreNode == null) {
            // Use HashMap to manipulate it.
            return entryStream.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        final Map<String, Entry<?>> sortedMap =
                entryStream.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                                     (v1, v2) -> v1, LinkedHashMap::new));
        // Use HashMap to manipulate it.
        final HashMap<String, Entry<?>> result = new HashMap<>(sortedMap.size());
        String lastIgnoredDirectory = null;
        for (Map.Entry<String, ? extends Entry<?>> entry : sortedMap.entrySet()) {
            final String path = entry.getKey();
            final boolean isDirectory = entry.getValue().type() == EntryType.DIRECTORY;
            assert ignoreNode != null;
            final MatchResult ignoreResult = ignoreNode.isIgnored(
                    path.substring(localPath().length()), isDirectory);
            if (ignoreResult == MatchResult.IGNORED) {
                if (isDirectory) {
                    lastIgnoredDirectory = path;
                }
                continue;
            }
            if (ignoreResult == MatchResult.CHECK_PARENT) {
                if (lastIgnoredDirectory != null && path.startsWith(lastIgnoredDirectory)) {
                    continue;
                }
            }
            result.put(path, entry.getValue());
        }

        return result;
    }

    private void addModifiedEntryToCache(Revision localHead, DirCache dirCache, ObjectReader reader,
                                         ObjectInserter inserter, TreeWalk treeWalk,
                                         int maxNumFiles, long maxNumBytes) throws IOException {
        final Map<String, Entry<?>> localHeadEntries = localHeadEntries(localHead);
        long numFiles = 0;
        long numBytes = 0;
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

            final Entry<?> entry = localHeadEntries.remove(localFilePath);
            if (entry == null) {
                // Remove a deleted entry.
                applyPathEdit(dirCache, new DeletePath(pathString));
                continue;
            }

            if (++numFiles > maxNumFiles) {
                throwMirrorException(maxNumFiles, "files");
                return;
            }

            final byte[] oldContent = currentEntryContent(reader, treeWalk);
            final long contentLength = applyPathEdit(dirCache, inserter, pathString, entry, oldContent);
            numBytes += contentLength;
            if (numBytes > maxNumBytes) {
                throwMirrorException(maxNumBytes, "bytes");
                return;
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
                throwMirrorException(maxNumFiles, "files");
                return;
            }

            final String convertedPath = remotePath().substring(1) + // Strip the leading '/'
                                         entry.getKey().substring(localPath().length());
            final long contentLength = applyPathEdit(dirCache, inserter, convertedPath, value, null);
            numBytes += contentLength;
            if (numBytes > maxNumBytes) {
                throwMirrorException(maxNumBytes, "bytes");
            }
        }
    }

    private static long applyPathEdit(DirCache dirCache, ObjectInserter inserter, String pathString,
                                      Entry<?> entry, @Nullable byte[] oldContent)
            throws JsonProcessingException {
        switch (EntryType.guessFromPath(pathString)) {
            case JSON:
                final JsonNode oldJsonNode = oldContent != null ? Jackson.readTree(oldContent) : null;
                final JsonNode newJsonNode = (JsonNode) entry.content();

                // Upsert only when the contents are really different.
                if (!Objects.equals(newJsonNode, oldJsonNode)) {
                    // Use InsertText to store the content in pretty format
                    final String newContent = newJsonNode.toPrettyString() + '\n';
                    applyPathEdit(dirCache, new InsertText(pathString, inserter, newContent));
                    return newContent.length();
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

    private <T> T throwMirrorException(long number, String filesOrBytes) {
        throw new MirrorException("mirror (" + remoteRepoUri() + '#' + remoteBranch() +
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

    private String getRemoteBranchName(Ref headBranchRef) {
        final String remoteBranch = remoteBranch();
        if (remoteBranch != null && !remoteBranch.isEmpty()) {
            return remoteBranch;
        }
        final String headBranchName = headBranchRef.getName();
        if (headBranchName.startsWith(Constants.R_HEADS)) {
            return headBranchName.substring(Constants.R_HEADS.length());
        }
        return headBranchName;
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
