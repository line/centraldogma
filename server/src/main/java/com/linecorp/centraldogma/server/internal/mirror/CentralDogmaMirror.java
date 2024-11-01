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

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.URI;
import java.time.Instant;

import javax.annotation.Nullable;

import com.cronutils.model.Cron;

import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public final class CentralDogmaMirror extends AbstractMirror {

    private final String remoteProject;
    private final String remoteRepo;

    public CentralDogmaMirror(String id, boolean enabled, Cron schedule, MirrorDirection direction,
                              Credential credential, Repository localRepo, String localPath,
                              URI remoteRepoUri, String remoteProject, String remoteRepo, String remotePath,
                              @Nullable String gitignore) {
        // Central Dogma has no notion of 'branch', so we just pass an empty string as a placeholder.
        super(id, enabled, schedule, direction, credential, localRepo, localPath, remoteRepoUri, remotePath,
              "", gitignore);

        this.remoteProject = requireNonNull(remoteProject, "remoteProject");
        this.remoteRepo = requireNonNull(remoteRepo, "remoteRepo");
    }

    String remoteProject() {
        return remoteProject;
    }

    String remoteRepo() {
        return remoteRepo;
    }

    @Override
    protected MirrorResult mirrorLocalToRemote(File workDir, int maxNumFiles, long maxNumBytes,
                                               Instant triggeredTime)
            throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected MirrorResult mirrorRemoteToLocal(File workDir, CommandExecutor executor,
                                               int maxNumFiles, long maxNumBytes, Instant triggeredTime)
            throws Exception {
        throw new UnsupportedOperationException();
    }
}
