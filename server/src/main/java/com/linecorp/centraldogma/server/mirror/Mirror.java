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

import java.io.File;
import java.net.URI;
import java.time.ZonedDateTime;

import javax.annotation.Nullable;

import com.cronutils.model.Cron;

import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * Contains the properties for a mirroring task and performs the task.
 */
public interface Mirror {

    /**
     * Returns the ID of the mirroring task.
     */
    @Nullable
    String id();

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
    @Nullable
    String remoteBranch();

    /**
     * Returns a <a href="https://git-scm.com/docs/gitignore">gitignore</a> pattern for the files
     * which won't be mirrored.
     */
    @Nullable
    String gitignore();

    /**
     * Returns whether this {@link Mirror} is enabled.
     */
    boolean enabled();

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
