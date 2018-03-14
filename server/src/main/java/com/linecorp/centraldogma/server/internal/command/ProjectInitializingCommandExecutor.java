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

import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.INTERNAL_PROJECT_NAME;
import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.generateSampleFiles;
import static com.linecorp.centraldogma.server.internal.metadata.MetadataService.METADATA_JSON;
import static com.linecorp.centraldogma.server.internal.storage.project.Project.REPO_MAIN;
import static com.linecorp.centraldogma.server.internal.storage.project.Project.REPO_META;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.metadata.Member;
import com.linecorp.centraldogma.server.internal.metadata.PerRolePermissions;
import com.linecorp.centraldogma.server.internal.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.internal.metadata.ProjectRole;
import com.linecorp.centraldogma.server.internal.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.internal.metadata.UserAndTimestamp;

public class ProjectInitializingCommandExecutor extends ForwardingCommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ProjectInitializingCommandExecutor.class);

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
                                                                                   projectName, REPO_META)))
                .thenCompose(unused -> delegate().execute(Command.createRepository(creationTimeMillis, author,
                                                                                   projectName, REPO_MAIN)))
                .thenCompose(unused -> initializeMetadata(delegate(), projectName, author))
                .thenCompose(unused -> generateSampleFiles(delegate(), projectName, REPO_MAIN))
                .thenApply(unused -> null);
    }

    private static CompletableFuture<Revision> initializeMetadata(CommandExecutor executor,
                                                                  String projectName,
                                                                  Author author) {
        // Do not generate a metadata file for internal projects.
        if (projectName.equals(INTERNAL_PROJECT_NAME)) {
            return CompletableFuture.completedFuture(Revision.INIT);
        }

        logger.info("Initializing metadata: {}", projectName);

        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);
        final RepositoryMetadata mainRepositoryMetadata =
                new RepositoryMetadata(REPO_MAIN, userAndTimestamp, PerRolePermissions.ofPublic());
        final Member member = new Member(author, ProjectRole.OWNER, userAndTimestamp);
        final ProjectMetadata metadata = new ProjectMetadata(projectName,
                                                             ImmutableMap.of(mainRepositoryMetadata.id(),
                                                                             mainRepositoryMetadata),
                                                             ImmutableMap.of(member.id(), member),
                                                             ImmutableMap.of(),
                                                             userAndTimestamp, null);
        return executor.execute(Command.push(
                Author.SYSTEM, projectName, REPO_META, Revision.HEAD,
                "Initialize metadata", "", Markup.PLAINTEXT,
                Change.ofJsonUpsert(METADATA_JSON, Jackson.valueToTree(metadata))));
    }
}
