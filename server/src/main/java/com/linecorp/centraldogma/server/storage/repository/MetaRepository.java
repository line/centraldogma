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

package com.linecorp.centraldogma.server.storage.repository;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.server.ZoneConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.mirror.Mirror;

/**
 * A Revision-controlled filesystem-like repository.
 */
public interface MetaRepository extends Repository {

    /**
     * Returns active mirroring tasks.
     */
    default CompletableFuture<List<Mirror>> mirrors() {
        return mirrors(false);
    }

    /**
     * Returns a set of mirroring tasks. If {@code includeDisabled} is {@code true}, disabled mirroring tasks
     * are also included in the returned {@link Mirror}s.
     */
    CompletableFuture<List<Mirror>> mirrors(boolean includeDisabled);

    /**
     * Returns a set of mirroring tasks of the specified repository. If {@code includeDisabled} is {@code true},
     * disabled mirroring tasks are also included in the returned {@link Mirror}s.
     */
    CompletableFuture<List<Mirror>> mirrors(String repoName, boolean includeDisabled);

    /**
     * Returns a mirroring task of the specified {@code id}.
     */
    default CompletableFuture<Mirror> mirror(String repoName, String id) {
        return mirror(repoName, id, Revision.HEAD);
    }

    /**
     * Returns a mirroring task of the specified {@code id} at the specified {@link Revision}.
     */
    CompletableFuture<Mirror> mirror(String repoName, String id, Revision revision);

    /**
     * Returns a list of project credentials.
     */
    CompletableFuture<List<Credential>> projectCredentials();

    /**
     * Returns a list of credentials of the specified repository.
     */
    CompletableFuture<List<Credential>> repoCredentials(String repoName);

    /**
     * Returns a credential of the specified {@code name}.
     */
    CompletableFuture<Credential> credential(String name);

    /**
     * Create a push {@link Command} for the {@link MirrorDto}.
     */
    CompletableFuture<Command<CommitResult>> createMirrorPushCommand(
            String repoName, MirrorRequest mirrorRequest, Author author,
            @Nullable ZoneConfig zoneConfig, boolean update);

    /**
     * Create a push {@link Command} for the {@link Credential}.
     */
    CompletableFuture<Command<CommitResult>> createCredentialPushCommand(Credential credential, Author author,
                                                                         boolean update);

    /**
     * Create a push {@link Command} for the {@link Credential}.
     */
    CompletableFuture<Command<CommitResult>> createCredentialPushCommand(String repoName, Credential credential,
                                                                         Author author, boolean update);
}
