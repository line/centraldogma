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

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;

/**
 * A Revision-controlled filesystem-like repository which is named {@code "meta"}.
 */
public interface MetaRepository extends Repository {

    /**
     * Returns active mirroring tasks.
     */
    default CompletableFuture<List<Mirror>> mirrors() {
        return mirrors(false);
    }

    /**
     * Returns a set of mirroring tasks. If {@code includeDisabled} is @{code true}, disabled mirroring tasks
     * are also included in the returned {@link Mirror}s.
     */
    CompletableFuture<List<Mirror>> mirrors(boolean includeDisabled);

    /**
     * Returns a mirroring task of the specified {@code id}.
     */
    CompletableFuture<Mirror> mirror(String id);

    /**
     * Create a push {@link Command} for the {@link MirrorDto}.
     */
    CompletableFuture<Command<CommitResult>> createCommand(MirrorDto mirrorDto, Author author, boolean update);

    /**
     * Returns a list of mirroring credentials.
     */
    CompletableFuture<List<MirrorCredential>> credentials();

    /**
     * Returns a mirroring credential of the specified {@code id}.
     */
    CompletableFuture<MirrorCredential> credential(String id);

    /**
     * Saves the {@link MirrorCredential}.
     */
    CompletableFuture<Revision> saveCredential(MirrorCredential credential, Author author);

    /**
     * Updates the {@link MirrorCredential}.
     */
    CompletableFuture<Revision> updateCredential(MirrorCredential credential, Author author);
}
