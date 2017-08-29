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

package com.linecorp.centraldogma.server.mirror;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.Cron;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.mirror.credential.MirrorCredential;
import com.linecorp.centraldogma.server.repository.Repository;

public abstract class Mirror {

    protected static final Author MIRROR_AUTHOR = new Author("Mirror", "mirror@localhost.localdomain");

    private static final Pattern DOGMA_PATH_PATTERN = Pattern.compile("^/([^/]+)/([^/]+)\\.dogma$");

    private static final String SCHEME_DOGMA = "dogma";

    static final String SCHEME_GIT = "git";
    static final String SCHEME_GIT_SSH = "git+ssh";
    static final String SCHEME_GIT_HTTP = "git+http";
    static final String SCHEME_GIT_HTTPS = "git+https";
    static final String SCHEME_GIT_FILE = "git+file";

    public static Mirror of(Cron schedule, MirrorDirection direction, MirrorCredential credential,
                            Repository localRepo, String localPath, URI remoteUri) {

        final String scheme = remoteUri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("no scheme in remoteUri: " + remoteUri);
        }

        switch (scheme) {
            case SCHEME_DOGMA: {
                final String[] components = split(remoteUri, "dogma", null);
                final URI remoteRepoUri = URI.create(components[0]);
                final Matcher matcher = DOGMA_PATH_PATTERN.matcher(remoteRepoUri.getPath());
                if (!matcher.find()) {
                    throw new IllegalArgumentException(
                            "cannot determine project name and repository name: " + remoteUri +
                            " (expected: dogma://<host>[:<port>]/<project>/<repository>.dogma[<remotePath>])");
                }

                final String remoteProject = matcher.group(1);
                final String remoteRepo = matcher.group(2);

                return new CentralDogmaMirror(schedule, direction, credential, localRepo, localPath,
                                              remoteRepoUri, remoteProject, remoteRepo, components[1]);
            }
            case SCHEME_GIT:
            case SCHEME_GIT_SSH:
            case SCHEME_GIT_HTTP:
            case SCHEME_GIT_HTTPS:
            case SCHEME_GIT_FILE: {
                final String[] components = split(remoteUri, "git", "master");
                return new GitMirror(schedule, direction, credential, localRepo, localPath,
                                     URI.create(components[0]), components[1], components[2]);
            }
        }

        throw new IllegalArgumentException("unsupported scheme in remoteUri: " + remoteUri);
    }

    /**
     * Splits the specified 'remoteUri' into:
     * - the actual remote repository URI
     * - the path in the remote repository
     * - the branch name.
     *
     * <p>e.g. git+ssh://foo.com/bar.git/some-path#master is split into:
     * - remoteRepoUri: git+ssh://foo.com/bar.git
     * - remotePath:    /some-path
     * - remoteBranch:  master
     *
     * <p>e.g. dogma://foo.com/bar/qux.dogma is split into:
     * - remoteRepoUri: dogma://foo.com/bar/qux.dogma
     * - remotePath:    / (default)
     * - remoteBranch:  {@code defaultBranch}
     */
    private static String[] split(URI remoteUri, String suffix, @Nullable String defaultBranch) {
        final String host = remoteUri.getHost();
        if (host == null && !remoteUri.getScheme().endsWith("+file")) {
            throw new IllegalArgumentException("no host in remoteUri: " + remoteUri);
        }

        final String path = remoteUri.getRawPath();
        if (path == null) {
            throw new IllegalArgumentException("no path in remoteUri: " + remoteUri);
        }

        final Matcher matcher = Pattern.compile("^(.*?\\." + suffix + ")(?:$|/)").matcher(path);
        if (!matcher.find()) {
            throw new IllegalArgumentException("no '." + suffix + "' in remoteUri path: " + remoteUri);
        }

        final String newRemoteUri;
        final int port = remoteUri.getPort();
        if (host != null) {
            if (port > 0) {
                newRemoteUri = remoteUri.getScheme() + "://" + host + ':' + port +
                               matcher.group(1);
            } else {
                newRemoteUri = remoteUri.getScheme() + "://" + host + matcher.group(1);
            }
        } else {
            newRemoteUri = remoteUri.getScheme() + "://" + matcher.group(1);
        }

        final String remotePath;
        try {
            String decoded = URLDecoder.decode(path.substring(matcher.group(1).length()), "UTF-8");
            decoded = normalizePath(decoded);

            remotePath = decoded;
        } catch (UnsupportedEncodingException e) {
            throw new Error(e);
        }

        final String fragment = remoteUri.getFragment();
        final String remoteBranch = fragment != null ? fragment : defaultBranch;

        return new String[] { newRemoteUri, remotePath, remoteBranch };
    }

    private final Cron schedule;
    private final MirrorDirection direction;
    private final MirrorCredential credential;
    private final Repository localRepo;
    private final String localPath;
    private final URI remoteRepoUri;
    private final String remotePath;
    private final String remoteBranch;

    protected Mirror(Cron schedule, MirrorDirection direction, MirrorCredential credential,
                     Repository localRepo, String localPath,
                     URI remoteRepoUri, String remotePath, @Nullable String remoteBranch) {

        this.schedule = requireNonNull(schedule, "schedule");
        this.direction = requireNonNull(direction, "direction");
        this.credential = requireNonNull(credential, "credential");
        this.localRepo = requireNonNull(localRepo, "localRepo");
        this.localPath = normalizePath(requireNonNull(localPath, "localPath"));
        this.remoteRepoUri = requireNonNull(remoteRepoUri, "remoteRepoUri");
        this.remotePath = normalizePath(requireNonNull(remotePath, "remotePath"));
        this.remoteBranch = remoteBranch;
    }

    private static String normalizePath(String path) {
        if (path.isEmpty()) {
            return "/";
        }

        if (!path.startsWith("/")) {
            path = '/' + path;
        }

        if (!path.endsWith("/")) {
            path += '/';
        }

        return path.replaceAll("//+", "/");
    }

    public final Cron schedule() {
        return schedule;
    }

    public MirrorDirection direction() {
        return direction;
    }

    public final MirrorCredential credential() {
        return credential;
    }

    public final Repository localRepo() {
        return localRepo;
    }

    public final String localPath() {
        return localPath;
    }

    public final URI remoteRepoUri() {
        return remoteRepoUri;
    }

    public final String remotePath() {
        return remotePath;
    }

    public final String remoteBranch() {
        return remoteBranch;
    }

    public final void mirror(File workDir, CommandExecutor executor, int maxNumFiles, long maxNumBytes) {
        try {
            switch (direction()) {
                case LOCAL_TO_REMOTE:
                    mirrorLocalToRemote(workDir, maxNumFiles, maxNumBytes);
                    break;
                case REMOTE_TO_LOCAL:
                    mirrorRemoteToLocal(workDir, executor, maxNumFiles, maxNumBytes);
                    break;
            }
        } catch (MirrorException e) {
            throw e;
        } catch (Exception e) {
            throw new MirrorException(e);
        }
    }

    protected abstract void mirrorLocalToRemote(
            File workDir, int maxNumFiles, long maxNumBytes) throws Exception;

    protected abstract void mirrorRemoteToLocal(
            File workDir, CommandExecutor executor, int maxNumFiles, long maxNumBytes) throws Exception;

    @Override
    public String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper("")
                                                 .add("schedule", CronDescriptor.instance().describe(schedule))
                                                 .add("direction", direction)
                                                 .add("localRepo", localRepo.name())
                                                 .add("localPath", localPath)
                                                 .add("remoteRepo", remoteRepoUri)
                                                 .add("remotePath", remotePath);
        if (remoteBranch != null) {
            helper.add("remoteBranch", remoteBranch);
        }

        helper.add("credential", credential);

        return helper.toString();
    }
}
