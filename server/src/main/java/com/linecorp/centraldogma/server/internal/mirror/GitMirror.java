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

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteSetUrlCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.Cron;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.mirror.credential.MirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredential;
import com.linecorp.centraldogma.server.internal.storage.repository.FindOption;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

public final class GitMirror extends Mirror {

    private static final Logger logger = LoggerFactory.getLogger(GitMirror.class);

    private static final Pattern DISALLOWED_CHARS = Pattern.compile("[^-_a-zA-Z]");
    private static final Pattern CONSECUTIVE_UNDERSCORES = Pattern.compile("_+");

    private static final int GIT_TIMEOUT_SECS = 10;

    GitMirror(Cron schedule, MirrorDirection direction, MirrorCredential credential,
              Repository localRepo, String localPath,
              URI remoteRepoUri, String remotePath, String remoteBranch) {
        super(schedule, direction, credential, localRepo, localPath, remoteRepoUri, remotePath, remoteBranch);
    }

    @Override
    protected void mirrorLocalToRemote(File workDir, int maxNumFiles, long maxNumBytes) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void mirrorRemoteToLocal(File workDir, CommandExecutor executor,
                                       int maxNumFiles, long maxNumBytes) throws Exception {

        final Map<String, Change<?>> changes = new HashMap<>();
        final String summary;

        try (Git git = openGit(workDir)) {
            final FetchCommand fetch = git.fetch();
            final String refName = Constants.R_HEADS + remoteBranch();
            final FetchResult fetchResult = fetch.setRefSpecs(new RefSpec(refName))
                                                 .setCheckFetchedObjects(true)
                                                 .setRemoveDeletedRefs(true)
                                                 .setTagOpt(TagOpt.NO_TAGS)
                                                 .setTimeout(GIT_TIMEOUT_SECS)
                                                 .call();

            final ObjectId id = fetchResult.getAdvertisedRef(refName).getObjectId();
            final RefUpdate refUpdate = git.getRepository().updateRef(refName);
            refUpdate.setNewObjectId(id);
            refUpdate.update();

            final Revision localRev = localRepo().normalizeNow(Revision.HEAD);

            try (ObjectReader reader = git.getRepository().newObjectReader();
                 TreeWalk treeWalk = new TreeWalk(reader);
                 RevWalk revWalk = new RevWalk(reader)) {

                // Prepare to traverse the tree.
                treeWalk.addTree(revWalk.parseTree(id).getId());

                // Check if local repository needs update.
                final String mirrorStatePath = localPath() + "mirror_state.json";
                final Entry<?> mirrorState = localRepo().getOrElse(localRev, mirrorStatePath, null).join();
                final String localSourceRevision;
                if (mirrorState == null || mirrorState.type() != EntryType.JSON) {
                    localSourceRevision = null;
                } else {
                    localSourceRevision = Jackson.treeToValue((TreeNode) mirrorState.content(),
                                                              MirrorState.class).sourceRevision();
                }

                final String abbrId = reader.abbreviate(id).name();
                if (id.name().equals(localSourceRevision)) {
                    logger.info("Repository '{}' already at {}, {}#{}", localRepo().name(), abbrId,
                                remoteRepoUri(), remoteBranch());
                    return;
                }

                // Add mirror_state.json.
                changes.put(mirrorStatePath, Change.ofJsonUpsert(
                        mirrorStatePath, "{ \"sourceRevision\": \"" + id.name() + "\" }"));

                // Construct the log message and log.
                summary = "Mirror " + abbrId + ", " + remoteRepoUri() + '#' + remoteBranch() +
                          " to the repository '" + localRepo().name() + '\'';
                logger.info(summary);

                long numFiles = 0;
                long numBytes = 0;
                while (treeWalk.next()) {
                    final String path = '/' + treeWalk.getPathString();

                    // Recurse into a directory if necessary.
                    if (treeWalk.isSubtree()) {
                        // Enter if the directory is under remotePath.
                        if (path.startsWith(remotePath())) {
                            treeWalk.enterSubtree();
                            continue;
                        }

                        // Enter if the directory is equal to remotePath.
                        final int pathLen = path.length() + 1; // Include the trailing '/'.
                        if (pathLen == remotePath().length() && remotePath().startsWith(path)) {
                            treeWalk.enterSubtree();
                            continue;
                        }

                        // Enter if the directory is parent of remotePath.
                        if (pathLen < remotePath().length() && remotePath().startsWith(path + '/')) {
                            treeWalk.enterSubtree();
                            continue;
                        }

                        // Skip the directory that are not under the remote path.
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
                        throw new MirrorException("mirror contains more than " + maxNumFiles + " file(s)");
                    }

                    final ObjectId objectId = treeWalk.getObjectId(0);
                    final long contentLength = reader.getObjectSize(objectId, ObjectReader.OBJ_ANY);
                    if (numBytes > maxNumBytes - contentLength) {
                        throw new MirrorException("mirror contains more than " + maxNumBytes + " byte(s)");
                    }
                    numBytes += contentLength;

                    final byte[] content = reader.open(objectId).getBytes();
                    switch (EntryType.guessFromPath(localPath)) {
                        case JSON:
                            final JsonNode jsonNode = Jackson.readTree(content);
                            changes.putIfAbsent(localPath, Change.ofJsonUpsert(localPath, jsonNode));
                            break;
                        case TEXT:
                            final String strVal = new String(content, StandardCharsets.UTF_8);
                            changes.putIfAbsent(localPath, Change.ofTextUpsert(localPath, strVal));
                            break;
                    }
                }
            }

            final Map<String, Entry<?>> oldEntries = localRepo().find(
                    localRev, localPath() + "**", ImmutableMap.of(FindOption.FETCH_CONTENT, false)).join();
            oldEntries.keySet().removeAll(changes.keySet());

            // Add the removed entries.
            oldEntries.forEach((path, entry) -> {
                if (entry.type() != EntryType.DIRECTORY && !changes.containsKey(path)) {
                    changes.put(path, Change.ofRemoval(path));
                }
            });

            executor.execute(Command.push(
                    MIRROR_AUTHOR, localRepo().parent().name(), localRepo().name(),
                    Revision.HEAD, summary, "", Markup.PLAINTEXT, changes.values())).join();
        }
    }

    private Git openGit(File workDir) throws Exception {
        // Convert the remoteRepoUri into the URI accepted by jGit by removing the 'git+' prefix.
        final String scheme = remoteRepoUri().getScheme();
        final String jGitUri;
        if (scheme.startsWith("git+")) {
            if (scheme.equals(SCHEME_GIT_SSH)) {
                // JSch requires the username to be included in the URI.
                final String username;
                if (credential() instanceof PasswordMirrorCredential) {
                    username = ((PasswordMirrorCredential) credential()).username();
                } else if (credential() instanceof PublicKeyMirrorCredential) {
                    username = ((PublicKeyMirrorCredential) credential()).username();
                } else {
                    username = null;
                }

                assert !remoteRepoUri().getRawAuthority().contains("@") : remoteRepoUri().getRawAuthority();
                if (username != null) {
                    jGitUri = "ssh://" + username + '@' + remoteRepoUri().getRawAuthority() +
                              remoteRepoUri().getRawPath();
                } else {
                    jGitUri = "ssh://" + remoteRepoUri().getRawAuthority() + remoteRepoUri().getRawPath();
                }
            } else {
                jGitUri = remoteRepoUri().toASCIIString().substring(4);
            }
        } else {
            jGitUri = remoteRepoUri().toASCIIString();
        }

        // Now create and open the repository.
        final File repoDir = new File(
                workDir,
                CONSECUTIVE_UNDERSCORES.matcher(DISALLOWED_CHARS.matcher(
                        remoteRepoUri().toASCIIString()).replaceAll("_")).replaceAll("_"));

        final GitWithAuth git = new GitWithAuth(this, repoDir);
        boolean success = false;
        try {
            // Set the remote URLs.
            final URIish uri = new URIish(jGitUri);
            final RemoteSetUrlCommand remoteSetUrl = git.remoteSetUrl();
            remoteSetUrl.setName(Constants.DEFAULT_REMOTE_NAME);
            remoteSetUrl.setUri(uri);

            remoteSetUrl.setPush(false);
            remoteSetUrl.call();

            remoteSetUrl.setPush(true);
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
}
