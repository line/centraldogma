/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.api;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.EncryptionKeyException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

public final class RepositoryServiceUtil {

    public static CompletableFuture<Revision> createRepository(
            CommandExecutor commandExecutor, MetadataService mds,
            Author author, String projectName, String repoName, boolean encryptionEnabled,
            @Nullable EncryptionStorageManager encryptionStorageManager) {
        if (!encryptionEnabled) {
            return commandExecutor.execute(Command.createRepository(author, projectName, repoName))
                                  .thenCompose(unused -> mds.addRepo(author, projectName, repoName));
        }

        assert encryptionStorageManager != null;

        final CompletableFuture<Revision> result = new CompletableFuture<>();
        final CompletableFuture<byte[]> wdekFuture = encryptionStorageManager.generateWdek();
        wdekFuture.handle((wdek, cause) -> {
            if (cause != null) {
                result.completeExceptionally(
                        new EncryptionKeyException("Failed to generate a new WDEK for " +
                                                   projectName + '/' + repoName, cause));
                return null;
            }
            commandExecutor.execute(Command.createRepository(author, projectName, repoName, wdek))
                           .thenCompose(unused -> mds.addRepo(author, projectName, repoName))
                           .handle((revision, cause2) -> {
                               if (cause2 != null) {
                                   result.completeExceptionally(cause2);
                                   return null;
                               }
                               result.complete(revision);
                               return null;
                           });
            return null;
        });
        return result;
    }

    public static CompletableFuture<Revision> removeRepository(
            CommandExecutor commandExecutor, MetadataService mds, Author author,
            String projectName, String repoName) {
        return commandExecutor.execute(Command.removeRepository(author, projectName, repoName))
                              .thenCompose(unused -> mds.removeRepo(author, projectName, repoName));
    }

    private RepositoryServiceUtil() {}
}
