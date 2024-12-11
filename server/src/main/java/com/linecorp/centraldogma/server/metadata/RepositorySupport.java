/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.centraldogma.server.metadata;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.command.ContentTransformer;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class RepositorySupport<T> {

    private final ProjectManager projectManager;
    private final CommandExecutor executor;
    private final Function<Entry<?>, T> entryConverter;

    RepositorySupport(ProjectManager projectManager, CommandExecutor executor,
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

    private CompletableFuture<HolderWithRevision<T>> fetch(Repository repository, String path) {
        requireNonNull(path, "path");
        final Revision revision = normalize(repository);
        return fetch(repository, path, revision);
    }

    private CompletableFuture<HolderWithRevision<T>> fetch(Repository repository, String path,
                                                           Revision revision) {
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

    private CompletableFuture<Revision> push(String projectName, String repoName, Author author,
                                             String commitSummary, Change<?> change, Revision revision) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(author, "author");
        requireNonNull(commitSummary, "commitSummary");
        requireNonNull(change, "change");

        return executor.execute(Command.push(author, projectName, repoName, revision, commitSummary, "",
                                             Markup.PLAINTEXT, ImmutableList.of(change)))
                       .thenApply(CommitResult::revision);
    }

    CompletableFuture<Revision> push(String projectName, String repoName,
                                     Author author, String commitSummary,
                                     ContentTransformer<JsonNode> transformer) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(author, "author");
        requireNonNull(commitSummary, "commitSummary");
        requireNonNull(transformer, "transformer");

        return executor.execute(Command.transform(null, author, projectName, repoName, Revision.HEAD,
                                                  commitSummary, "", Markup.PLAINTEXT, transformer))
                       .thenApply(CommitResult::revision);
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
