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

package com.linecorp.centraldogma.server.storage.project;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.server.command.Command.createProject;
import static com.linecorp.centraldogma.server.command.Command.createRepository;
import static com.linecorp.centraldogma.server.command.Command.push;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.Tokens;

/**
 * Initializes the internal project and repositories.
 */
public final class InternalProjectInitializer {

    public static final String INTERNAL_PROJECT_DOGMA = "dogma";

    private final CommandExecutor executor;
    private final ProjectManager projectManager;
    private final CompletableFuture<Void> initialFuture = new CompletableFuture<>();

    /**
     * Creates a new instance.
     */
    public InternalProjectInitializer(CommandExecutor executor, ProjectManager projectManager) {
        this.executor = executor;
        this.projectManager = projectManager;
    }

    /**
     * Creates an internal project and repositories and a token storage to {@code dogma/dogma/tokens.json}.
     */
    public void initialize() {
        try {
            initialize0(INTERNAL_PROJECT_DOGMA);
            initializeTokens();
            initialFuture.complete(null);
        } catch (Exception cause) {
            initialFuture.completeExceptionally(cause);
        }
    }

    /**
     * Creates the specified internal project and its internal repositories.
     */
    public void initialize(String projectName) {
        requireNonNull(projectName, "projectName");
        checkArgument(!INTERNAL_PROJECT_DOGMA.equals(projectName),
                      "Use initialize() to create %s", projectName);
        initialize0(projectName);
    }

    /**
     * Creates an internal project and repositories such as a token storage.
     */
    public void initialize0(String projectName) {
        if (projectManager.exists(projectName)) {
            return;
        }

        final long creationTimeMillis = System.currentTimeMillis();
        try {
            executor.execute(createProject(creationTimeMillis, Author.SYSTEM, projectName))
                    .get();
        } catch (Throwable cause) {
            final Throwable peeled = Exceptions.peel(cause);
            if (peeled instanceof ReadOnlyException) {
                // The executor has stopped right after starting up.
                return;
            }
            if (!(peeled instanceof ProjectExistsException)) {
                throw new Error("failed to initialize an internal project: " + projectName, peeled);
            }
        }

        // These repositories might be created when creating an internal project, but we try to create them
        // again here in order to make sure them exist because sometimes their names are changed.
        initializeInternalRepos(projectName, Project.internalRepos(), creationTimeMillis);
    }

    private void initializeTokens() {
        try {
            final Change<?> change = Change.ofJsonPatch(MetadataService.TOKEN_JSON,
                                                        null, Jackson.valueToTree(new Tokens()));
            final String commitSummary = "Initialize the token list file: /" + INTERNAL_PROJECT_DOGMA + '/' +
                                         Project.REPO_DOGMA + MetadataService.TOKEN_JSON;
            executor.execute(push(Author.SYSTEM, INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, Revision.HEAD,
                                  commitSummary, "", Markup.PLAINTEXT, ImmutableList.of(change)))
                    .get();
        } catch (Throwable cause) {
            final Throwable peeled = Exceptions.peel(cause);
            if (peeled instanceof ReadOnlyException || peeled instanceof ChangeConflictException) {
                return;
            }
            throw new Error("failed to initialize the token list file", peeled);
        }
    }

    /**
     * Returns a {@link CompletableFuture} which is completed when the internal project and repositories are
     * ready.
     */
    public CompletableFuture<Void> whenInitialized() {
        return initialFuture;
    }

    /**
     * Creates the specified internal repositories in the internal project.
     */
    private void initializeInternalRepos(String projectName, List<String> internalRepos,
                                         long creationTimeMillis) {
        requireNonNull(internalRepos, "internalRepos");
        for (final String repo : internalRepos) {
            try {
                executor.execute(createRepository(creationTimeMillis, Author.SYSTEM, projectName, repo))
                        .get();
            } catch (Throwable cause) {
                final Throwable peeled = Exceptions.peel(cause);
                if (peeled instanceof ReadOnlyException) {
                    // The executor has stopped right after starting up.
                    return;
                }
                if (!(peeled instanceof RepositoryExistsException)) {
                    throw new Error("failed to initialize an internal repository: " + projectName +
                                    '/' + repo, peeled);
                }
            }
        }
    }
}
