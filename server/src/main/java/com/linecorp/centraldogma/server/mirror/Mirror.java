/*
 * Copyright 2019 LINE Corporation
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

import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_DOGMA;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_FILE;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTP;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTPS;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_SSH;
import static com.linecorp.centraldogma.server.mirror.MirrorUtil.DOGMA_PATH_PATTERN;
import static com.linecorp.centraldogma.server.mirror.MirrorUtil.split;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;

import javax.annotation.Nullable;

import com.cronutils.model.Cron;

import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.mirror.CentralDogmaMirror;
import com.linecorp.centraldogma.server.internal.mirror.GitMirror;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * Contains the properties for a mirroring task and performs the task.
 */
public interface Mirror {

    /**
     * Creates a new instance.
     *
     * @param schedule a cron expression that describes when the mirroring task is supposed to be triggered.
     *                 If unspecified, {@code 0 * * * * ?} (every minute) is used.
     * @param direction the direction of mirror
     * @param credential the authentication credentials which are required when accessing the Git repositories
     * @param localRepo the Central Dogma repository name
     * @param localPath the directory path in the {@code localRepo}
     * @param remoteUri the URI of the Git repository which will be mirrored from
     * @param remoteExcludePath the relative path to {@code remoteUri} which will not be mirrored
     */
    static Mirror of(Cron schedule, MirrorDirection direction, MirrorCredential credential,
                     Repository localRepo, String localPath, URI remoteUri,
                     @Nullable String remoteExcludePath) {
        requireNonNull(schedule, "schedule");
        requireNonNull(direction, "direction");
        requireNonNull(credential, "credential");
        requireNonNull(localRepo, "localRepo");
        requireNonNull(localPath, "localPath");
        requireNonNull(remoteUri, "remoteUri");

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
                                              remoteRepoUri, remoteProject, remoteRepo, components[1],
                                              remoteExcludePath);
            }
            case SCHEME_GIT:
            case SCHEME_GIT_SSH:
            case SCHEME_GIT_HTTP:
            case SCHEME_GIT_HTTPS:
            case SCHEME_GIT_FILE: {
                final String[] components = split(remoteUri, "git", "master");
                return new GitMirror(schedule, direction, credential, localRepo, localPath,
                                     URI.create(components[0]), components[1], components[2],
                                     remoteExcludePath);
            }
        }

        throw new IllegalArgumentException("unsupported scheme in remoteUri: " + remoteUri);
    }

    /**
     * Returns the schedule for the mirroring task.
     */
    Cron schedule();

    /**
     * Returns the next execution time of the mirroring task.
     *
     * @param lastExecutionTime the last execution time of the mirroring task
     */
    ZonedDateTime nextExecutionTime(ZonedDateTime lastExecutionTime);

    /**
     * Returns the direction of the mirroring task.
     */
    MirrorDirection direction();

    /**
     * Returns the authentication credentials which are required when accessing the Git repositories.
     */
    MirrorCredential credential();

    /**
     * Returns the Central Dogma repository where is supposed to keep the mirrored files.
     */
    Repository localRepo();

    /**
     * Returns the path in the Central Dogma repository where is supposed to keep the mirrored files.
     */
    String localPath();

    /**
     * Returns the URI of the Git repository which will be mirrored from.
     */
    URI remoteRepoUri();

    /**
     * Returns the path of the Git repository where is supposed to be mirrored.
     */
    String remotePath();

    /**
     * Returns the name of the branch in the Git repository where is supposed to be mirrored.
     */
    String remoteBranch();

    /**
     * Returns the relative path to {@code remotePath} where isn't supposed to be mirrored.
     */
    String remoteExcludePath();

    /**
     * Performs the mirroring task.
     *
     * @param workDir the local directory where keeps the mirrored files
     * @param executor the {@link CommandExecutor} which is used to perform operation to the Central Dogma
     *                 storage
     * @param maxNumFiles the maximum number of files allowed to the mirroring task. A {@link MirrorException}
     *                    would be raised if the number of files to be mirrored exceeds it.
     * @param maxNumBytes the maximum bytes allowed to the mirroring task. A {@link MirrorException} would be
     *                    raised if the total size of the files to be mirrored exceeds it.
     */
    void mirror(File workDir, CommandExecutor executor, int maxNumFiles, long maxNumBytes);
}
