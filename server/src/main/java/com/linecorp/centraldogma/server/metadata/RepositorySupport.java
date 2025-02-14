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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.command.ContentTransformer;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.CacheableCall;
import com.linecorp.centraldogma.server.storage.repository.HasWeight;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class RepositorySupport<T> {

    private final ProjectManager projectManager;
    private final CommandExecutor executor;
    private final Class<T> entryClass;

    RepositorySupport(ProjectManager projectManager, CommandExecutor executor,
                      Class<T> entryClass) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.executor = requireNonNull(executor, "executor");
        this.entryClass = requireNonNull(entryClass, "entryClass");
    }

    public ProjectManager projectManager() {
        return projectManager;
    }

    CompletableFuture<HolderWithRevision<T>> fetch(String projectName, String repoName, String path) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        return fetch(projectManager().get(projectName).repos().get(repoName), path);
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
        final CacheableFetchCall<T> cacheableFetchCall = new CacheableFetchCall<>(repository, revision, path,
                                                                                  entryClass);
        return repository.execute(cacheableFetchCall);
    }

    CompletableFuture<Revision> push(String projectName, String repoName,
                                     Author author, String commitSummary, Change<?> change) {
        return push(projectName, repoName, author, commitSummary, change, Revision.HEAD);
    }

    private CompletableFuture<Revision> push(String projectName, String repoName, Author author,
                                             String commitSummary, Change<?> change, Revision revision) {
        requireNonNull(change, "change");
        return push(projectName, repoName, author, commitSummary, ImmutableList.of(change), revision);
    }

    private CompletableFuture<Revision> push(String projectName, String repoName, Author author,
                                             String commitSummary, Iterable<Change<?>> changes,
                                             Revision revision) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");
        requireNonNull(author, "author");
        requireNonNull(commitSummary, "commitSummary");
        requireNonNull(changes, "changes");

        return executor.execute(Command.push(author, projectName, repoName, revision, commitSummary, "",
                                             Markup.PLAINTEXT, changes))
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
                       .thenApply(CommitResult::revision)
                       .exceptionally(cause -> {
                           final Throwable peeled = Exceptions.peel(cause);
                           if (peeled instanceof RedundantChangeException) {
                               final Revision revision = ((RedundantChangeException) peeled).headRevision();
                               assert revision != null;
                               return revision;
                           }
                           return Exceptions.throwUnsafely(peeled);
                       });
    }

    static Revision normalize(Repository repository) {
        requireNonNull(repository, "repository");
        try {
            return repository.normalizeNow(Revision.HEAD);
        } catch (Throwable cause) {
            return Exceptions.throwUnsafely(cause);
        }
    }

    // TODO(minwoox): Consider generalizing this class.
    private static class CacheableFetchCall<U> implements CacheableCall<HolderWithRevision<U>> {

        private final Repository repo;
        private final Revision revision;
        private final String path;
        private final Class<U> entryClass;
        private final int hashCode;

        CacheableFetchCall(Repository repo, Revision revision, String path, Class<U> entryClass) {
            this.repo = repo;
            this.revision = revision;
            this.path = path;
            this.entryClass = entryClass;

            hashCode = Objects.hash(revision, path, entryClass) * 31 + System.identityHashCode(repo);
            assert !revision.isRelative();
        }

        @Override
        public int weigh(HolderWithRevision<U> value) {
            int weight = path.length();
            final U object = value.object();
            if (object instanceof HasWeight) {
                weight += ((HasWeight) object).weight();
            }
            return weight;
        }

        @Override
        public CompletableFuture<HolderWithRevision<U>> execute() {
            return repo.get(revision, path)
                       .thenApply(this::convertWithJackson)
                       .thenApply((U obj) -> HolderWithRevision.of(obj, revision));
        }

        @SuppressWarnings("unchecked")
        U convertWithJackson(Entry<?> entry) {
            requireNonNull(entry, "entry");
            try {
                return Jackson.treeToValue(((Entry<JsonNode>) entry).content(), entryClass);
            } catch (Throwable cause) {
                return Exceptions.throwUnsafely(cause);
            }
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            final CacheableFetchCall<?> that = (CacheableFetchCall<?>) o;
            return revision.equals(that.revision) &&
                   path.equals(that.path) &&
                   entryClass == that.entryClass;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("repo", repo)
                              .add("revision", revision)
                              .add("path", path)
                              .add("entryClass", entryClass)
                              .toString();
        }
    }
}
