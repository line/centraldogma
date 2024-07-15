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

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;

public final class RepositoryServiceUtil {

    public static CompletableFuture<Revision> createRepository(
            CommandExecutor commandExecutor, MetadataService mds,
            Author author, String projectName, String repoName) {
        return commandExecutor.execute(Command.createRepository(author, projectName, repoName))
                              .thenCompose(unused -> mds.addRepo(author, projectName, repoName));
    }

    public static CompletableFuture<Revision> removeRepository(
            CommandExecutor commandExecutor, MetadataService mds, Author author,
            String projectName, String repoName) {
        return commandExecutor.execute(Command.removeRepository(author, projectName, repoName))
                              .thenCompose(unused -> mds.removeRepo(author, projectName, repoName));
    }

    private RepositoryServiceUtil() {}
}
