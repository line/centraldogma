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

import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_DOGMA_HTTPS;
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ALL_WITHOUT_CONTENT;
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ALL_WITH_CONTENT;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.Cron;
import com.cronutils.utils.VisibleForTesting;
import com.fasterxml.jackson.core.TreeNode;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MirrorException;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.internal.credential.AccessTokenCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorStatus;
import com.linecorp.centraldogma.server.mirror.RepositoryUri;
import com.linecorp.centraldogma.server.storage.repository.FindOption;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public final class CentralDogmaMirror extends AbstractMirror {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaMirror.class);

    private final String remoteProject;
    private final String remoteRepo;

    // Injected by MirrorSchedulingService before each run; null only in tests / direct usage.
    @Nullable
    private volatile ConcurrentHashMap<String, Object> baseClientPool;

    public CentralDogmaMirror(String id, boolean enabled, Cron schedule, MirrorDirection direction,
                              Credential credential, Repository localRepo, String localPath,
                              RepositoryUri remoteUri, String remoteProject, String remoteRepo,
                              @Nullable String gitignore, @Nullable String zone) {
        super(id, enabled, schedule, direction, credential, localRepo, localPath,
              remoteUri, gitignore, zone);

        this.remoteProject = requireNonNull(remoteProject, "remoteProject");
        this.remoteRepo = requireNonNull(remoteRepo, "remoteRepo");
    }

    @Override
    public void setBaseClientPool(ConcurrentHashMap<String, Object> pool) {
        baseClientPool = pool;
    }

    @VisibleForTesting
    String remoteProject() {
        return remoteProject;
    }

    @VisibleForTesting
    String remoteRepo() {
        return remoteRepo;
    }

    private static String baseClientPoolKey(URI uri) {
        return uri.getHost() + ':' + uri.getPort() + ':' +
               SCHEME_DOGMA_HTTPS.equals(uri.getScheme());
    }

    // maxNumBytes comes from MirrorSchedulingService.maxNumBytesPerMirror, which is fixed at server startup.
    private synchronized CentralDogma createRemoteClient(long maxNumBytes) throws UnknownHostException {
        final URI uri = remoteUri().uri();
        final ConcurrentHashMap<String, Object> pool = baseClientPool;

        final Credential cred = credential();
        final String token = cred instanceof AccessTokenCredential ?
                             ((AccessTokenCredential) cred).accessToken() : CsrfToken.ANONYMOUS;

        CentralDogma base;
        if (pool != null) {
            // Reuse or create a shared base client (MirrorSchedulingService owns the pool lifecycle).
            final String key = baseClientPoolKey(uri);
            final Object existing = pool.get(key);
            if (existing instanceof CentralDogma) {
                base = (CentralDogma) existing;
            } else {
                base = createClient(uri, maxNumBytes, null);
                final Object prev = pool.putIfAbsent(key, base);
                if (prev instanceof CentralDogma) {
                    // Lost the race; close ours and reuse the winner.
                    try {
                        base.close();
                    } catch (Exception e) {
                        logger.warn("Failed to close the redundant base CentralDogma client: {}", uri, e);
                    }
                    base = (CentralDogma) prev;
                }
            }

            // TODO(minwoox) Support mTLS authentication as well.
            return base.withAccessToken(token);
        }
        // No pool injected (tests or direct usage without the scheduler).
        return createClient(uri, maxNumBytes, token);
    }

    private static CentralDogma createClient(URI uri, long maxResponseLength, @Nullable String token)
            throws UnknownHostException {
        final ArmeriaCentralDogmaBuilder builder = new ArmeriaCentralDogmaBuilder();
        if (uri.getPort() > 0) {
            builder.host(uri.getHost(), uri.getPort());
        } else {
            builder.host(uri.getHost());
        }
        builder.useTls(SCHEME_DOGMA_HTTPS.equals(uri.getScheme()));
        builder.clientConfigurator(cb -> cb.maxResponseLength(maxResponseLength));
        // Mirrors run on a fixed schedule; a failed run simply retries on the next tick.
        // Persistent health-check traffic is unnecessary and wasteful at scale.
        builder.healthCheckIntervalMillis(0);
        if (token != null) {
            builder.accessToken(token);
        }
        return builder.build();
    }

    @Override
    protected MirrorResult mirrorLocalToRemote(File workDir, int maxNumFiles, long maxNumBytes,
                                               Instant triggeredTime) throws Exception {
        final Revision localHead = localRepo().normalizeNow(Revision.HEAD);

        try (CentralDogma remote = createRemoteClient(maxNumBytes)) {
            final CentralDogmaRepository repo = remote.forRepo(remoteProject, remoteRepo);
            // Get remote HEAD revision.
            final Revision remoteHead = repo.normalize(Revision.HEAD).join();

            // Load old MirrorState from remote.
            final String mirrorStatePath = remotePath() + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME;
            final MirrorState oldMirrorState = loadRemoteMirrorState(repo, mirrorStatePath, remoteHead);

            // Determine whether to run.
            final MirrorDecision decision = shouldRunLocalToRemote(
                    oldMirrorState, localHead, remoteHead);
            if (decision == MirrorDecision.SKIP) {
                final String description = String.format(
                        "The remote repository '%s' already at %s. Local repository: '%s/%s'",
                        remoteUri(), localHead, localRepo().parent().name(), localRepo().name());
                logger.debug(description);
                return newMirrorResult(MirrorStatus.UP_TO_DATE, description, triggeredTime);
            }

            // Read local files under localPath and apply gitignore filter.
            final Map<String, Entry<?>> localEntries = filterByGitignore(
                    localRepo().find(localHead, localPath() + "**", FIND_ALL_WITH_CONTENT).join(),
                    localPath());

            // Fetch remote files for comparison/removal detection.
            final Map<String, Entry<?>> remoteEntries =
                    repo.file(PathPattern.of(remotePath() + "**")).viewRaw(true).get(remoteHead).join();

            // Build changes.
            final List<Change<?>> changes = new ArrayList<>();
            long numFiles = 0;
            long numBytes = 0;

            for (Map.Entry<String, Entry<?>> entry : localEntries.entrySet()) {
                final String localFilePath = entry.getKey();
                final Entry<?> localEntry = entry.getValue();

                if (localEntry.type() == EntryType.DIRECTORY) {
                    continue;
                }

                // Skip the mirror state file.
                if (localFilePath.endsWith(MIRROR_STATE_FILE_NAME)) {
                    continue;
                }

                // Map local path to remote path.
                final String remoteFilePath = remotePath() +
                                              localFilePath.substring(localPath().length());

                if (!Util.isValidFilePath(remoteFilePath)) {
                    continue;
                }

                if (++numFiles > maxNumFiles) {
                    throw newMirrorException(maxNumFiles, "files");
                }

                final String content = localEntry.contentAsText();
                if (content != null) {
                    numBytes += content.length();
                    if (numBytes > maxNumBytes) {
                        throw newMirrorException(maxNumBytes, "bytes");
                    }
                }

                changes.add(newChange(remoteFilePath, localEntry));
            }

            // Detect removals: files on remote that don't have a corresponding local file.
            for (Map.Entry<String, Entry<?>> entry : remoteEntries.entrySet()) {
                final String remoteFilePath = entry.getKey();
                final Entry<?> remoteEntry = entry.getValue();

                if (remoteEntry.type() == EntryType.DIRECTORY) {
                    continue;
                }

                // Skip the mirror state file.
                if (remoteFilePath.endsWith(LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME)) {
                    continue;
                }

                // Map remote path to local path.
                final String localFilePath = localPath() +
                                             remoteFilePath.substring(remotePath().length());

                if (!localEntries.containsKey(localFilePath)) {
                    changes.add(Change.ofRemoval(remoteFilePath));
                }
            }

            // Add the mirror state file.
            final MirrorState newMirrorState = new MirrorState(
                    localHead.text(), remoteHead.text(), localHead.text(),
                    MirrorDirection.LOCAL_TO_REMOTE, hashString());
            changes.add(Change.ofJsonUpsert(mirrorStatePath,
                                            Jackson.valueToTree(newMirrorState)));

            if (decision == MirrorDecision.COMPARE_AND_RUN &&
                !hasLocalToRemoteChanges(changes, remoteEntries)) {
                final String description = String.format(
                        "The remote repository '%s' already at %s. Local repository: '%s/%s'",
                        remoteUri(), localHead, localRepo().parent().name(), localRepo().name());
                logger.debug(description);
                return newMirrorResult(MirrorStatus.UP_TO_DATE, description, triggeredTime);
            }

            final String summary = "Mirror '" + localRepo().name() + "' at " + localHead +
                                   " to the repository '" + remoteUri() + '\'';

            try {
                repo.commit(summary, changes).push(Revision.HEAD).join();
                logger.info(summary);
                return newMirrorResult(MirrorStatus.SUCCESS, summary, triggeredTime);
            } catch (Throwable t) {
                final Throwable peeled = Exceptions.peel(t);
                if (peeled instanceof RedundantChangeException) {
                    return newMirrorResult(MirrorStatus.UP_TO_DATE, summary, triggeredTime);
                }
                return Exceptions.throwUnsafely(peeled);
            }
        } catch (Throwable t) {
            final Throwable peeled = Exceptions.peel(t);
            if (peeled instanceof MirrorException) {
                return Exceptions.throwUnsafely(peeled);
            }
            throw new MirrorException("unexpected exception while mirror local to remote repository. " +
                                      "local: " + localRepo().parent().name() + '/' + localRepo().name() +
                                      ", remote: " + remoteProject + '/' + remoteRepo, t);
        }
    }

    @Override
    protected MirrorResult mirrorRemoteToLocal(File workDir, CommandExecutor executor,
                                               int maxNumFiles, long maxNumBytes, Instant triggeredTime)
            throws Exception {
        final Revision localRev = localRepo().normalizeNow(Revision.HEAD);
        final String mirrorStatePath = localPath() + MIRROR_STATE_FILE_NAME;

        try (CentralDogma remote = createRemoteClient(maxNumBytes)) {
            final CentralDogmaRepository repo = remote.forRepo(remoteProject, remoteRepo);
            // Get remote HEAD revision.
            final Revision remoteHead = repo.normalize(Revision.HEAD).join();

            // Load old MirrorState from local repo.
            final MirrorState oldMirrorState = loadLocalMirrorState(mirrorStatePath, localRev);

            // Determine whether to run.
            final MirrorDecision decision = shouldRunRemoteToLocal(oldMirrorState, localRev, remoteHead);
            if (decision == MirrorDecision.SKIP) {
                final String description = String.format(
                        "Repository '%s/%s' already at %s, %s",
                        localRepo().parent().name(), localRepo().name(),
                        remoteHead, remoteUri());
                logger.debug(description);
                return newMirrorResult(MirrorStatus.UP_TO_DATE, description, triggeredTime);
            }

            // Fetch all remote files under remotePath and apply gitignore filter.
            final Map<String, Entry<?>> remoteEntries = filterByGitignore(
                    repo.file(PathPattern.of(remotePath() + "**")).viewRaw(true).get(remoteHead).join(),
                    remotePath());

            // Build Change objects.
            final Map<String, Change<?>> changes = new HashMap<>();

            // Add mirror_state.json.
            final MirrorState newMirrorState = new MirrorState(
                    remoteHead.text(), remoteHead.text(), localRev.text(),
                    MirrorDirection.REMOTE_TO_LOCAL, hashString());
            changes.put(mirrorStatePath,
                        Change.ofJsonUpsert(mirrorStatePath, Jackson.valueToTree(newMirrorState)));

            long numFiles = 0;
            long numBytes = 0;

            for (Map.Entry<String, Entry<?>> entry : remoteEntries.entrySet()) {
                final String remoteFilePath = entry.getKey();
                final Entry<?> remoteEntry = entry.getValue();

                if (remoteEntry.type() == EntryType.DIRECTORY) {
                    continue;
                }

                // Skip files not under remotePath.
                if (!remoteFilePath.startsWith(remotePath())) {
                    continue;
                }

                // Map remote path to local path.
                final String localFilePath = localPath() +
                                             remoteFilePath.substring(remotePath().length());

                if (!Util.isValidFilePath(localFilePath)) {
                    continue;
                }

                if (++numFiles > maxNumFiles) {
                    throw newMirrorException(maxNumFiles, "files");
                }

                final String content = remoteEntry.contentAsText();
                if (content != null) {
                    numBytes += content.length();
                    if (numBytes > maxNumBytes) {
                        throw newMirrorException(maxNumBytes, "bytes");
                    }
                }

                changes.put(localFilePath, newChange(localFilePath, remoteEntry));
            }

            // Detect removals.
            final Map<FindOption<?>, ?> findOptions =
                    decision == MirrorDecision.COMPARE_AND_RUN ? FIND_ALL_WITH_CONTENT
                                                               : FIND_ALL_WITHOUT_CONTENT;
            final Map<String, Entry<?>> oldEntries =
                    localRepo().find(localRev, localPath() + "**", findOptions).join();

            if (decision == MirrorDecision.COMPARE_AND_RUN) {
                if (!hasRemoteToLocalChanges(changes, oldEntries)) {
                    final String description = String.format(
                            "Repository '%s/%s' already at %s, %s",
                            localRepo().parent().name(), localRepo().name(),
                            remoteHead, remoteUri());
                    logger.debug(description);
                    return newMirrorResult(MirrorStatus.UP_TO_DATE, description, triggeredTime);
                }
            }

            oldEntries.keySet().removeAll(changes.keySet());
            oldEntries.forEach((path, entry) -> {
                if (entry.type() != EntryType.DIRECTORY && !changes.containsKey(path)) {
                    changes.put(path, Change.ofRemoval(path));
                }
            });
            validateChanges(changes);

            final String summary = "Mirror " + remoteHead + ", '" + remoteUri() +
                                   "' to the repository '" + localRepo().name() + '\'';
            try {
                final Revision revision = executor.execute(Command.push(
                        MIRROR_AUTHOR, localRepo().parent().name(), localRepo().name(),
                        Revision.HEAD, summary, "", Markup.PLAINTEXT,
                        changes.values())).join();
                final String description = summary + ", revision: " + revision.text();
                return newMirrorResult(MirrorStatus.SUCCESS, description, triggeredTime);
            } catch (Throwable t) {
                final Throwable peeled = Exceptions.peel(t);
                if (peeled instanceof RedundantChangeException) {
                    return newMirrorResult(MirrorStatus.UP_TO_DATE, summary, triggeredTime);
                }
                return Exceptions.throwUnsafely(peeled);
            }
        } catch (Throwable t) {
            final Throwable peeled = Exceptions.peel(t);
            if (peeled instanceof MirrorException) {
                return Exceptions.throwUnsafely(peeled);
            }
            throw new MirrorException("unexpected exception while mirror remote to local repository. " +
                                      "local: " + localRepo().parent().name() + '/' + localRepo().name() +
                                      ", remote: " + remoteProject + '/' + remoteRepo, t);
        }
    }

    private MirrorDecision shouldRunLocalToRemote(@Nullable MirrorState oldMirrorState,
                                                  Revision localHead,
                                                  Revision remoteHead) {
        if (oldMirrorState == null) {
            return MirrorDecision.RUN;
        }
        if (!hashString().equals(oldMirrorState.configHash())) {
            return MirrorDecision.RUN;
        }
        if (oldMirrorState.remoteRevision() == null || oldMirrorState.localRevision() == null) {
            return MirrorDecision.RUN;
        }
        if (!localHead.text().equals(oldMirrorState.localRevision())) {
            return MirrorDecision.RUN;
        }

        // Use backward(1) because the previous mirroring itself created a commit on the remote.
        if (!remoteHead.backward(1).text().equals(oldMirrorState.remoteRevision())) {
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

    private MirrorDecision shouldRunRemoteToLocal(@Nullable MirrorState oldMirrorState,
                                                  Revision localHead,
                                                  Revision remoteHead) {
        if (oldMirrorState == null) {
            return MirrorDecision.RUN;
        }
        if (!hashString().equals(oldMirrorState.configHash())) {
            return MirrorDecision.RUN;
        }
        if (oldMirrorState.remoteRevision() == null || oldMirrorState.localRevision() == null) {
            return MirrorDecision.RUN;
        }
        if (!remoteHead.text().equals(oldMirrorState.remoteRevision())) {
            return MirrorDecision.RUN;
        }
        // Use backward(1) because the previous mirroring itself created a commit on the local.
        if (!localHead.backward(1).text().equals(oldMirrorState.localRevision())) {
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

    @Nullable
    private static MirrorState loadRemoteMirrorState(CentralDogmaRepository repo, String mirrorStatePath,
                                                     Revision remoteHead) {
        final Entry<?> entry;
        try {
            entry = repo.file(mirrorStatePath).get(remoteHead).join();
            if (entry.type() != EntryType.JSON) {
                return null;
            }
        } catch (Exception e) {
            final Throwable peeled = Exceptions.peel(e);
            if (peeled instanceof EntryNotFoundException) {
                return null;
            }
            return Exceptions.throwUnsafely(peeled);
        }
        try {
            return Jackson.treeToValue((TreeNode) entry.content(), MirrorState.class);
        } catch (Exception e) {
            logger.warn("Failed to load mirror state from remote: {}", mirrorStatePath, e);
            return null;
        }
    }

    @Nullable
    private MirrorState loadLocalMirrorState(String mirrorStatePath, Revision localRev) {
        final Entry<?> entry = localRepo().getOrNull(localRev, mirrorStatePath).join();
        if (entry == null || entry.type() != EntryType.JSON) {
            return null;
        }
        try {
            return Jackson.treeToValue((TreeNode) entry.content(), MirrorState.class);
        } catch (Exception e) {
            logger.warn("Failed to load mirror state from local: {}", mirrorStatePath, e);
            return null;
        }
    }

    private static Change<?> newChange(String path, Entry<?> entry) {
        switch (EntryType.guessFromPath(path)) {
            case JSON:
                return Change.ofJsonUpsert(path, entry.contentAsText());
            case YAML:
                return Change.ofYamlUpsert(path, entry.contentAsText());
            case TEXT:
            default:
                return Change.ofTextUpsert(path, entry.contentAsText());
        }
    }

    private MirrorException newMirrorException(long limit, String filesOrBytes) {
        return new MirrorException(
                "mirror (id: " + id() + ", localRepo: " + localRepo().parent().name() +
                '/' + localRepo().name() + ", remoteUri: " + remoteUri() +
                ") contains more than " + limit + ' ' + filesOrBytes);
    }

    private static boolean hasLocalToRemoteChanges(List<Change<?>> changes,
                                                   Map<String, Entry<?>> remoteEntries) {
        for (Change<?> change : changes) {
            final String path = change.path();
            if (path.endsWith(LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME)) {
                continue;
            }
            // A removal is always a real change.
            if (change.type() == ChangeType.REMOVE) {
                return true;
            }
            final Entry<?> remoteEntry = remoteEntries.get(path);
            if (remoteEntry == null) {
                // New file added.
                return true;
            }
            final String newContent = change.contentAsText();
            final String oldContent = remoteEntry.contentAsText();
            if (newContent != null && !newContent.equals(oldContent)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRemoteToLocalChanges(Map<String, Change<?>> newChanges,
                                                   Map<String, Entry<?>> oldEntries) {
        for (Change<?> change : newChanges.values()) {
            final String path = change.path();
            if (path.endsWith(MIRROR_STATE_FILE_NAME)) {
                continue;
            }
            if (!oldEntries.containsKey(path)) {
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
            if (!newChanges.containsKey(path)) {
                return true;
            }
        }
        for (Change<?> change : newChanges.values()) {
            final String path = change.path();
            if (path.endsWith(MIRROR_STATE_FILE_NAME)) {
                continue;
            }
            final Entry<?> oldEntry = oldEntries.get(path);
            if (oldEntry == null) {
                continue;
            }
            final String newContent = change.contentAsText();
            final String oldContent = oldEntry.contentAsText();
            if (newContent != null && !newContent.equals(oldContent)) {
                return true;
            }
        }
        return false;
    }
}
