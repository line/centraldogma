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

import static com.linecorp.centraldogma.server.mirror.MirrorUtil.normalizePath;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.URI;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nullable;

import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public abstract class AbstractMirror implements Mirror {

    private static final CronDescriptor CRON_DESCRIPTOR = CronDescriptor.instance();

    protected static final Author MIRROR_AUTHOR = new Author("Mirror", "mirror@localhost.localdomain");

    private final String id;
    private final boolean enabled;
    private final Cron schedule;
    private final MirrorDirection direction;
    private final Credential credential;
    private final Repository localRepo;
    private final String localPath;
    private final URI remoteRepoUri;
    private final String remotePath;
    private final String remoteBranch;
    @Nullable
    private final String gitignore;
    private final ExecutionTime executionTime;
    private final long jitterMillis;

    protected AbstractMirror(String id, boolean enabled, Cron schedule, MirrorDirection direction,
                             Credential credential, Repository localRepo, String localPath,
                             URI remoteRepoUri, String remotePath, String remoteBranch,
                             @Nullable String gitignore) {
        this.id = requireNonNull(id, "id");
        this.enabled = enabled;
        this.schedule = requireNonNull(schedule, "schedule");
        this.direction = requireNonNull(direction, "direction");
        this.credential = requireNonNull(credential, "credential");
        this.localRepo = requireNonNull(localRepo, "localRepo");
        this.localPath = normalizePath(requireNonNull(localPath, "localPath"));
        this.remoteRepoUri = requireNonNull(remoteRepoUri, "remoteRepoUri");
        this.remotePath = normalizePath(requireNonNull(remotePath, "remotePath"));
        this.remoteBranch = requireNonNull(remoteBranch, "remoteBranch");
        this.gitignore = gitignore;

        executionTime = ExecutionTime.forCron(this.schedule);

        // Pre-calculate a constant jitter value up to 1 minute for a mirror.
        // Use the properties' hash code so that the same properties result in the same jitter.
        jitterMillis = Math.abs(Objects.hash(this.schedule.asString(), this.direction,
                                             this.localRepo.parent().name(), this.localRepo.name(),
                                             this.remoteRepoUri, this.remotePath, this.remoteBranch) /
                                (Integer.MAX_VALUE / 60000));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public final Cron schedule() {
        return schedule;
    }

    @Override
    public final ZonedDateTime nextExecutionTime(ZonedDateTime lastExecutionTime) {
        return nextExecutionTime(lastExecutionTime, jitterMillis);
    }

    @VisibleForTesting
    ZonedDateTime nextExecutionTime(ZonedDateTime lastExecutionTime, long jitterMillis) {
        requireNonNull(lastExecutionTime, "lastExecutionTime");
        final Optional<ZonedDateTime> next = executionTime.nextExecution(
                lastExecutionTime.minus(jitterMillis, ChronoUnit.MILLIS));
        if (next.isPresent()) {
            return next.get().plus(jitterMillis, ChronoUnit.MILLIS);
        }
        throw new IllegalArgumentException(
                "no next execution time for " + CRON_DESCRIPTOR.describe(schedule) + ", lastExecutionTime: " +
                lastExecutionTime);
    }

    @Override
    public MirrorDirection direction() {
        return direction;
    }

    @Override
    public final Credential credential() {
        return credential;
    }

    @Override
    public final Repository localRepo() {
        return localRepo;
    }

    @Override
    public final String localPath() {
        return localPath;
    }

    @Override
    public final URI remoteRepoUri() {
        return remoteRepoUri;
    }

    @Override
    public final String remotePath() {
        return remotePath;
    }

    @Override
    public final String remoteBranch() {
        return remoteBranch;
    }

    @Override
    public final String gitignore() {
        return gitignore;
    }

    @Override
    public final boolean enabled() {
        return enabled;
    }

    @Override
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
        } catch (InterruptedException e) {
            // Propagate the interruption.
            Thread.currentThread().interrupt();
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
                                                 .omitNullValues()
                                                 .add("schedule", CronDescriptor.instance().describe(schedule))
                                                 .add("direction", direction)
                                                 .add("localProj", localRepo.parent().name())
                                                 .add("localRepo", localRepo.name())
                                                 .add("localPath", localPath)
                                                 .add("remoteRepo", remoteRepoUri)
                                                 .add("remotePath", remotePath)
                                                 .add("remoteBranch", remoteBranch)
                                                 .add("gitignore", gitignore)
                                                 .add("credential", credential);

        return helper.toString();
    }
}
