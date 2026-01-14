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

import static com.linecorp.centraldogma.server.metadata.RepositoryMetadata.DEFAULT_PROJECT_ROLES;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.metadata.Roles;
import com.linecorp.centraldogma.server.metadata.UserAndTimestamp;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageException;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;

public final class RepositoryServiceUtil {

    public static CompletableFuture<Revision> createRepository(
            CommandExecutor commandExecutor, MetadataService mds,
            Author author, String projectName, String repoName, boolean encrypt,
            @Nullable EncryptionStorageManager encryptionStorageManager) {
        final Map<String, RepositoryRole> users;
        final Map<String, RepositoryRole> appIds;
        if (author.isToken()) {
            users = ImmutableMap.of();
            // author.name() is the appId of the token.
            appIds = ImmutableMap.of(author.name(), RepositoryRole.ADMIN);
        } else {
            users = ImmutableMap.of(author.email(), RepositoryRole.ADMIN);
            appIds = ImmutableMap.of();
        }

        final Roles roles = new Roles(DEFAULT_PROJECT_ROLES, users, null, appIds);
        final RepositoryMetadata repositoryMetadata =
                RepositoryMetadata.of(repoName, roles, UserAndTimestamp.of(author));

        if (!encrypt) {
            return commandExecutor.execute(Command.createRepository(author, projectName, repoName))
                                  .thenCompose(unused -> mds.addRepo(
                                          author, projectName, repoName, repositoryMetadata));
        }

        assert encryptionStorageManager != null;

        return encryptionStorageManager.generateWdek()
                                       .thenCompose(wdek -> {
                                           final WrappedDekDetails wrappedDekDetails = new WrappedDekDetails(
                                                   wdek, 1, encryptionStorageManager.kekId(),
                                                   projectName, repoName);
                                           return commandExecutor.execute(Command.createRepository(
                                                   author, projectName, repoName, wrappedDekDetails));
                                       })
                                       .thenCompose(unused -> mds.addRepo(
                                               author, projectName, repoName, repositoryMetadata))
                                       .exceptionally(cause -> {
                                           if (cause instanceof EncryptionStorageException) {
                                               throw (EncryptionStorageException) cause;
                                           }
                                           throw new EncryptionStorageException(
                                                   "Failed to create encrypted repository " +
                                                   projectName + '/' + repoName, cause);
                                       });
    }

    public static CompletableFuture<Revision> removeRepository(
            CommandExecutor commandExecutor, MetadataService mds, Author author,
            String projectName, String repoName) {
        return commandExecutor.execute(Command.removeRepository(author, projectName, repoName))
                              .thenCompose(unused -> mds.removeRepo(author, projectName, repoName));
    }

    private RepositoryServiceUtil() {}
}
