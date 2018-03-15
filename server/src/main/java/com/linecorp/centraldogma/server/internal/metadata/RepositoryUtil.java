/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.metadata;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.ChangeConflictException;
import com.linecorp.centraldogma.server.internal.storage.repository.RedundantChangeException;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

final class RepositoryUtil<T> {

    private final ProjectManager projectManager;
    private final CommandExecutor executor;
    private final Function<Entry<?>, T> entryConverter;

    RepositoryUtil(ProjectManager projectManager, CommandExecutor executor,
                   Function<Entry<?>, T> entryConverter) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.executor = requireNonNull(executor, "executor");
        this.entryConverter = requireNonNull(entryConverter, "entryConverter");
    }

    public ProjectManager projectManager() {
        return projectManager;
    }

    CompletableFuture<HolderWithRevision<T>> fetch(String projectName, String repoName, String path) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        return fetch(projectManager().get(projectName).repos().get(repoName), path);
    }

    CompletableFuture<HolderWithRevision<T>> fetch(String projectName, String repoName, String path,
                                                   Revision revision) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(revision, "revision");
        return fetch(projectManager().get(projectName).repos().get(repoName), path, revision);
    }

    CompletableFuture<HolderWithRevision<T>> fetch(Repository repository, String path) {
        requireNonNull(path, "path");
        final Revision revision = normalize(repository);
        return fetch(repository, path, revision);
    }

    CompletableFuture<HolderWithRevision<T>> fetch(Repository repository, String path, Revision revision) {
        requireNonNull(repository, "repository");
        requireNonNull(path, "path");
        requireNonNull(revision, "revision");
        return repository.get(revision, path)
                         .thenApply(entryConverter)
                         .thenApply((T obj) -> HolderWithRevision.of(obj, revision));
    }

    CompletableFuture<Revision> push(String projectName, String repoName,
                                     Author author, String commitSummary, Change<?> change) {
        return push(projectName, repoName, author, commitSummary, change, Revision.HEAD);
    }

    CompletableFuture<Revision> push(String projectName, String repoName,
                                     Author author, String commitSummary, Change<?> change, Revision revision) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(author, "author");
        requireNonNull(commitSummary, "commitSummary");
        requireNonNull(change, "change");

        final CompletableFuture<Map<String, Change<?>>> f =
                projectManager().get(projectName).repos().get(repoName)
                                .previewDiff(revision, ImmutableList.of(change));
        return f.thenCompose(changes -> executor.execute(
                Command.push(author, projectName, repoName, revision, commitSummary, "",
                             Markup.PLAINTEXT, changes.values())));
    }

    CompletableFuture<Revision> push(String projectName, String repoName, Author author, String commitSummary,
                                     Supplier<CompletionStage<HolderWithRevision<Change<?>>>> changeSupplier) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(author, "author");
        requireNonNull(commitSummary, "commitSummary");
        requireNonNull(changeSupplier, "changeSupplier");

        final CompletableFuture<Revision> future = new CompletableFuture<>();
        push(projectName, repoName, author, commitSummary, changeSupplier, future);
        return future;
    }

    private void push(String projectName, String repoName, Author author, String commitSummary,
                      Supplier<CompletionStage<HolderWithRevision<Change<?>>>> changeSupplier,
                      CompletableFuture<Revision> future) {
        changeSupplier.get().thenAccept(changeWithRevision -> {
            final Revision revision = changeWithRevision.revision();
            final Change<?> change = changeWithRevision.object();

            push(projectName, repoName, author, commitSummary, change, revision)
                    .thenAccept(future::complete)
                    .exceptionally(voidFunction(cause -> {
                        cause = Exceptions.peel(cause);
                        if (cause instanceof ChangeConflictException) {
                            final Revision latestRevision;
                            try {
                                latestRevision = projectManager().get(projectName).repos().get(repoName)
                                                                 .normalizeNow(Revision.HEAD);
                            } catch (Throwable cause1) {
                                future.completeExceptionally(cause1);
                                return;
                            }

                            if (revision.equals(latestRevision)) {
                                future.completeExceptionally(cause);
                                return;
                            }
                            // Try again.
                            push(projectName, repoName, author, commitSummary, changeSupplier, future);
                        } else if (cause instanceof RedundantChangeException) {
                            future.complete(revision);
                        } else {
                            future.completeExceptionally(cause);
                        }
                    }));
        }).exceptionally(voidFunction(future::completeExceptionally));
    }

    Revision normalize(Repository repository) {
        requireNonNull(repository, "repository");
        try {
            return repository.normalizeNow(Revision.HEAD);
        } catch (Throwable cause) {
            return Exceptions.throwUnsafely(cause);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T convertWithJackson(Entry<?> entry, Class<T> clazz) {
        requireNonNull(entry, "entry");
        requireNonNull(clazz, "clazz");
        try {
            return Jackson.treeToValue(((Entry<JsonNode>) entry).content(), clazz);
        } catch (Throwable cause) {
            return Exceptions.throwUnsafely(cause);
        }
    }
}
