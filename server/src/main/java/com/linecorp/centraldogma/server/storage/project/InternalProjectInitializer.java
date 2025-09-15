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
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.centraldogma.server.command.Command.createProject;
import static com.linecorp.centraldogma.server.command.Command.createRepository;
import static com.linecorp.centraldogma.server.command.Command.push;
import static com.linecorp.centraldogma.server.metadata.MetadataService.TOKEN_JSON;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.Tokens;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryListener;

/**
 * Initializes the internal project and repositories.
 */
public final class InternalProjectInitializer {

    private static final Logger logger = LoggerFactory.getLogger(InternalProjectInitializer.class);

    public static final String INTERNAL_PROJECT_DOGMA = "dogma";

    private final CommandExecutor executor;
    private final ProjectManager projectManager;
    private final CompletableFuture<Void> initialFuture = new CompletableFuture<>();

    @Nullable
    private volatile Revision lastTokensRevision;
    @Nullable
    private volatile Tokens tokens;

    /**
     * Creates a new instance.
     */
    public InternalProjectInitializer(CommandExecutor executor, ProjectManager projectManager) {
        this.executor = executor;
        this.projectManager = projectManager;
    }

    /**
     * Loads the tokens from {@code dogma/dogma/tokens.json} and sets up a listener to keep it updated.
     */
    public void initializeInReadOnlyMode() {
        try {
            if (!projectManager.exists(INTERNAL_PROJECT_DOGMA)) {
                throw new IllegalStateException(
                        INTERNAL_PROJECT_DOGMA + " project does not exists. " +
                        "Cannot initialize in read-only mode.");
            }
            final Repository dogmaRepo = projectManager.get(INTERNAL_PROJECT_DOGMA)
                                                       .repos()
                                                       .get(Project.REPO_DOGMA);
            final Entry<JsonNode> entry = dogmaRepo.getOrNull(Revision.HEAD, Query.ofJson(TOKEN_JSON)).join();
            if (entry == null || !entry.hasContent()) {
                throw new IllegalStateException(
                        TOKEN_JSON + " file does not exist in " + INTERNAL_PROJECT_DOGMA +
                        '/' + Project.REPO_DOGMA + ". Cannot initialize in read-only mode.");
            }
            setTokens(entry, dogmaRepo);
            initialFuture.complete(null);
        } catch (Throwable t) {
            initialFuture.completeExceptionally(t);
            Exceptions.throwUnsafely(t);
        }
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
    private void initialize0(String projectName) {
        final long creationTimeMillis = System.currentTimeMillis();
        if (!projectManager.exists(projectName)) {
            try {
                executor.execute(Command.forcePush(
                                createProject(creationTimeMillis, Author.SYSTEM, projectName)))
                        .get();
            } catch (Throwable cause) {
                final Throwable peeled = Exceptions.peel(cause);
                if (!(peeled instanceof ProjectExistsException)) {
                    throw new Error("failed to initialize an internal project: " + projectName, peeled);
                }
            }
        }

        // These repositories might be created when creating an internal project, but we try to create them
        // again here in order to make sure them exist because sometimes their names are changed.
        initializeInternalRepos(projectName, Project.internalRepos(), creationTimeMillis);
    }

    private void initializeTokens() {
        final Repository dogmaRepo = projectManager.get(INTERNAL_PROJECT_DOGMA).repos().get(Project.REPO_DOGMA);
        final Entry<JsonNode> entry = dogmaRepo.getOrNull(Revision.HEAD, Query.ofJson(TOKEN_JSON)).join();
        if (entry != null && entry.hasContent()) {
            setTokens(entry, dogmaRepo);
            return;
        }
        try {
            final Change<?> change = Change.ofJsonPatch(TOKEN_JSON,
                                                        null, Jackson.valueToTree(new Tokens()));
            final String commitSummary = "Initialize the token list file: /" + INTERNAL_PROJECT_DOGMA + '/' +
                                         Project.REPO_DOGMA + TOKEN_JSON;
            executor.execute(Command.forcePush(push(Author.SYSTEM, INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA,
                                                    Revision.HEAD, commitSummary, "", Markup.PLAINTEXT,
                                                    ImmutableList.of(change))))
                    .get();
            final Entry<JsonNode> entry1 = dogmaRepo.getOrNull(Revision.HEAD, Query.ofJson(TOKEN_JSON)).join();
            assert entry1 != null;
            setTokens(entry1, dogmaRepo);
        } catch (Throwable cause) {
            final Throwable peeled = Exceptions.peel(cause);
            if (peeled instanceof ChangeConflictException) {
                return;
            }
            throw new Error("failed to initialize the token list file", peeled);
        }
    }

    private void setTokens(Entry<JsonNode> entry, Repository dogmaRepo) {
        try {
            final Tokens tokens = Jackson.treeToValue(entry.content(), Tokens.class);
            lastTokensRevision = entry.revision();
            this.tokens = tokens;
            attachTokensListener(dogmaRepo);
        } catch (JsonParseException | JsonMappingException e) {
            throw new RuntimeException(String.format("failed to parse %s/%s/%s", INTERNAL_PROJECT_DOGMA,
                                                     Project.REPO_DOGMA, TOKEN_JSON), e);
        }
    }

    private void attachTokensListener(Repository dogmaRepo) {
        dogmaRepo.addListener(RepositoryListener.of(Query.ofJson(TOKEN_JSON), entry -> {
            if (entry == null) {
                logger.warn("{} file is missing in {}/{}", TOKEN_JSON, INTERNAL_PROJECT_DOGMA,
                            Project.REPO_DOGMA);
                return;
            }

            final Revision lastRevision = entry.revision();
            final Revision lastTokensRevision = this.lastTokensRevision;
            if (lastTokensRevision != null && lastRevision.compareTo(lastTokensRevision) <= 0) {
                // An old data.
                return;
            }

            try {
                final Tokens tokens = Jackson.treeToValue(entry.content(), Tokens.class);
                this.lastTokensRevision = lastRevision;
                this.tokens = tokens;
            } catch (JsonParseException | JsonMappingException e) {
                logger.warn("Invalid {} file in {}/{}", TOKEN_JSON, INTERNAL_PROJECT_DOGMA,
                            Project.REPO_DOGMA, e);
            }
        }));
    }

    /**
     * Returns a {@link CompletableFuture} which is completed when the internal project and repositories are
     * ready.
     */
    public CompletableFuture<Void> whenInitialized() {
        return initialFuture;
    }

    /**
     * Returns the {@link Tokens}.
     */
    public Tokens tokens() {
        final Tokens tokens = this.tokens;
        checkState(tokens != null, "tokens have not been loaded yet");
        return tokens;
    }

    /**
     * Creates the specified internal repositories in the internal project.
     */
    private void initializeInternalRepos(String projectName, List<String> internalRepos,
                                         long creationTimeMillis) {
        requireNonNull(internalRepos, "internalRepos");
        final Project project = projectManager.get(projectName);
        assert project != null;
        for (final String repo : internalRepos) {
            if (project.repos().exists(repo)) {
                continue;
            }
            try {
                executor.execute(Command.forcePush(
                                createRepository(creationTimeMillis, Author.SYSTEM, projectName, repo)))
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
