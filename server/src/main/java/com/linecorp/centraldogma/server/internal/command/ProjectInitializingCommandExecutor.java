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

package com.linecorp.centraldogma.server.internal.command;

import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.generateSampleFiles;
import static com.linecorp.centraldogma.server.internal.storage.project.Project.REPO_MAIN;
import static com.linecorp.centraldogma.server.internal.storage.project.Project.REPO_META;

import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.common.Author;

public class ProjectInitializingCommandExecutor extends ForwardingCommandExecutor {

    public ProjectInitializingCommandExecutor(CommandExecutor delegate) {
        super(delegate);
    }

    @Override
    public <T> CompletableFuture<T> execute(Command<T> command) {
        if (!(command instanceof CreateProjectCommand)) {
            return super.execute(command);
        }

        final CreateProjectCommand c = (CreateProjectCommand) command;
        final String projectName = c.projectName();
        final long creationTimeMillis = c.timestamp();
        final Author author = c.author();

        final CompletableFuture<Void> f = delegate().execute(c);
        return f.thenCompose(unused -> delegate().execute(Command.createRepository(creationTimeMillis, author,
                                                                                   projectName, REPO_META
        )))
                .thenCompose(unused -> delegate().execute(Command.createRepository(creationTimeMillis, author,
                                                                                   projectName, REPO_MAIN
                )))
                .thenCompose(unused -> generateSampleFiles(delegate(), projectName, REPO_MAIN))
                .thenApply(unused -> null);
    }
}
