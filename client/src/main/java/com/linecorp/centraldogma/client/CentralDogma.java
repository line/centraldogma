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
package com.linecorp.centraldogma.client;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;

/**
 * Central Dogma client.
 */
public interface CentralDogma {

    /**
     * Creates a new {@link CentralDogma} instance using the default {@link ClientFactory}.
     *
     * @param uri the URI of the Central Dogma server. e.g. tbinary+http://example.com:36462/cd/thrift/v1
     */
    static CentralDogma newClient(String uri) {
        return new CentralDogmaBuilder().uri(uri).build();
    }

    /**
     * Creates a new {@link CentralDogma} instance.
     *
     * @param clientFactory the {@link ClientFactory} that will manage the connection
     * @param uri the URI of the Central Dogma server. e.g. tbinary+http://example.com:36462/cd/thrift/v1
     */
    static CentralDogma newClient(ClientFactory clientFactory, String uri) {
        return new CentralDogmaBuilder().clientFactory(requireNonNull(clientFactory, "clientFactory"))
                                        .uri(requireNonNull(uri, "uri"))
                                        .build();
    }

    /**
     * Creates a project.
     */
    CompletableFuture<Void> createProject(String name);

    /**
     * Removes a project.
     */
    CompletableFuture<Void> removeProject(String name);

    /**
     * Unremoves a project.
     */
    CompletableFuture<Void> unremoveProject(String name);

    /**
     * Retrieves the list of the projects.
     */
    CompletableFuture<Set<String>> listProjects();

    /**
     * Retrieves the list of the removed projects.
     */
    CompletableFuture<Set<String>> listRemovedProjects();

    /**
     * Creates a repository.
     */
    CompletableFuture<Void> createRepository(String projectName, String repositoryName);

    /**
     * Removes a repository.
     */
    CompletableFuture<Void> removeRepository(String projectName, String repositoryName);

    /**
     * Unremoves a repository.
     */
    CompletableFuture<Void> unremoveRepository(String projectName, String repositoryName);

    /**
     * Retrieves the list of the repositories.
     */
    CompletableFuture<Map<String, RepositoryInfo>> listRepositories(String projectName);

    /**
     * Retrieves the list of the removed repositories.
     */
    CompletableFuture<Set<String>> listRemovedRepositories(String projectName);

    /**
     * Converts the relative revision number to the absolute revision number.
     * (e.g. {@code -1 -> 3}, {@code -1.-1 -> 3.4})
     */
    CompletableFuture<Revision> normalizeRevision(String projectName, String repositoryName, Revision revision);

    /**
     * Retrieves the list of the files in the path.
     */
    CompletableFuture<Map<String, EntryType>> listFiles(String projectName, String repositoryName,
                                                        Revision revision, String pathPattern);

    /**
     * Retrieves a file at the specified revision. This method is a shortcut of
     * {@code getFile(getFile(projectName, repositoryName, revision, Query.identity(path))}.
     */
    default CompletableFuture<Entry<Object>> getFile(String projectName, String repositoryName,
                                                     Revision revision, String path) {
        return getFile(projectName, repositoryName, revision, Query.identity(path));
    }

    /**
     * Queries a file at the specified revision.
     */
    <T> CompletableFuture<Entry<T>> getFile(String projectName, String repositoryName,
                                            Revision revision, Query<T> query);

    /**
     * Retrieves the files that match the path pattern.
     */
    CompletableFuture<Map<String, Entry<?>>> getFiles(String projectName, String repositoryName,
                                                      Revision revision, String pathPattern);

    /**
     * Retrieves the history of the repository.
     */
    CompletableFuture<List<CommitAndChanges<?>>> getHistory(
            String projectName, String repositoryName, Revision from, Revision to, String pathPattern);

    /**
     * Queries a file at two different revisions and return the diff of the two query results.
     */
    <T> CompletableFuture<Change<T>> getDiff(String projectName, String repositoryName,
                                             Revision from, Revision to, Query<T> query);

    /**
     * @deprecated Use {@link #getDiff(String, String, Revision, Revision, Query)} instead.
     */
    @Deprecated
    default <T> CompletableFuture<Change<T>> diffFile(String projectName, String repositoryName,
                                                      Revision from, Revision to, Query<T> query) {
        return getDiff(projectName, repositoryName, from, to, query);
    }

    /**
     * Retrieves the diffs matched by the path pattern from {@code from} to {@code to}.
     */
    CompletableFuture<List<Change<?>>> getDiffs(String projectName, String repositoryName,
                                                Revision from, Revision to, String pathPattern);

    /**
     * Retrieves preview diffs on {@code baseRevision} for {@code changes}.
     */
    default CompletableFuture<List<Change<?>>> getPreviewDiffs(String projectName, String repositoryName,
                                                               Revision baseRevision, Change<?>... changes) {
        return getPreviewDiffs(projectName, repositoryName, baseRevision, ImmutableList.copyOf(changes));
    }

    /**
     * Retrieves preview diffs on {@code baseRevision} for {@code changes}.
     */
    CompletableFuture<List<Change<?>>> getPreviewDiffs(String projectName, String repositoryName,
                                                       Revision baseRevision,
                                                       Iterable<? extends Change<?>> changes);

    /**
     * Pushes the changes to the repository.
     */
    default CompletableFuture<Commit> push(String projectName, String repositoryName, Revision baseRevision,
                                           Author author, String summary, Change<?>... changes) {
        return push(projectName, repositoryName, baseRevision, author, summary, ImmutableList.copyOf(changes));
    }

    /**
     * Pushes the changes to the repository.
     */
    default CompletableFuture<Commit> push(String projectName, String repositoryName, Revision baseRevision,
                                           Author author, String summary,
                                           Iterable<? extends Change<?>> changes) {
        return push(projectName, repositoryName, baseRevision, author, summary, "", Markup.PLAINTEXT, changes);
    }

    /**
     * Pushes the changes to the repository.
     */
    default CompletableFuture<Commit> push(String projectName, String repositoryName, Revision baseRevision,
                                           Author author, String summary, String detail, Markup markup,
                                           Change<?>... changes) {
        return push(projectName, repositoryName, baseRevision, author, summary, detail, markup,
                    ImmutableList.copyOf(changes));
    }

    /**
     * Pushes the changes to the repository.
     */
    CompletableFuture<Commit> push(String projectName, String repositoryName, Revision baseRevision,
                                   Author author, String summary, String detail, Markup markup,
                                   Iterable<? extends Change<?>> changes);

    /**
     * Awaits and returns the latest known revision since the specified revision.
     */
    CompletableFuture<Revision> watchRepository(String projectName, String repositoryName,
                                                Revision lastKnownRevision, String pathPattern,
                                                long timeoutMillis);

    /**
     * Awaits and returns the query result of the specified file since the specified last known revision.
     */
    <T> CompletableFuture<QueryResult<T>> watchFile(String projectName, String repositoryName,
                                                    Revision lastKnownRevision, Query<T> query,
                                                    long timeoutMillis);

    /**
     * Returns a {@link Watcher} which notifies its listeners when the result of the
     * given {@link Query} becomes available or changes. e.g:
     * <pre>{@code
     * Watcher<Object> watcher = client.fileWatcher("foo", "bar", Query.identity("/baz.json"));
     *
     * watcher.watch((revision, content) -> {
     *     JsonNode json = (JsonNode) content;
     *     ...
     * });}</pre>
     */
    default <T> Watcher<T> fileWatcher(String projectName, String repositoryName, Query<T> query) {
        return fileWatcher(projectName, repositoryName, query, Function.identity());
    }

    /**
     * Returns a {@link Watcher} which notifies its listeners after applying the specified
     * {@link Function} when the result of the given {@link Query} becomes available or changes. e.g:
     * <pre>{@code
     * Watcher<MyType> watcher = client.fileWatcher(
     *         "foo", "bar", Query.identity("/baz.json"),
     *         content -> new ObjectMapper().treeToValue((TreeNode) content, MyType.class));
     *
     * watcher.watch((revision, myValue) -> {
     *     assert myValue instanceof MyType;
     *     ...
     * });}</pre>
     */
    <T, U> Watcher<U> fileWatcher(String projectName, String repositoryName,
                                  Query<T> query, Function<? super T, ? extends U> function);

    /**
     * Returns a {@link Watcher} which notifies its listeners when the repository that matched
     * the given {@code pathPattern} becomes available or changes. e.g:
     * <pre>{@code
     * Watcher<Revision> watcher = client.repositoryWatcher("foo", "bar", "/*.json");
     *
     * watcher.watch(revision -> {
     *     ...
     * });}</pre>
     */
    default Watcher<Revision> repositoryWatcher(String projectName, String repositoryName, String pathPattern) {
        return repositoryWatcher(projectName, repositoryName, pathPattern, Function.identity());
    }

    /**
     * Returns a {@link Watcher} which notifies its listeners when the repository that matched
     * the given {@code pathPattern} becomes available or changes. e.g:
     * <pre>{@code
     * Watcher<Map<String, Entry<?>> watcher = client.repositoryWatcher(
     *         "foo", "bar", "/*.json", revision -> client.getFiles(revision).get());
     *
     * watcher.watch((revision, contents) -> {
     *     ...
     * });}</pre>
     */
    <T> Watcher<T> repositoryWatcher(String projectName, String repositoryName, String pathPattern,
                                     Function<Revision, ? extends T> function);
}
