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
     * Returns a newly-created {@link CentralDogma} instance which connects to the given host at port 36462
     * using the default {@link ClientFactory}.
     *
     * @param host the host name or IP address of the Central Dogma server
     */
    static CentralDogma forHost(String host) {
        return new CentralDogmaBuilder().host(host).build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance which connects to the given host and port
     * using the default {@link ClientFactory}.
     *
     * @param host the host name or IP address of the Central Dogma server
     * @param port the port number of the Central Dogma server
     */
    static CentralDogma forHost(String host, int port) {
        return new CentralDogmaBuilder().host(host, port).build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance which connects to the given host at port 36462
     * using the specified {@link ClientFactory}.
     *
     * @param clientFactory the {@link ClientFactory} that will manage the connections
     * @param host the host name or IP address of the Central Dogma server
     */
    static CentralDogma forHost(ClientFactory clientFactory, String host) {
        return new CentralDogmaBuilder().clientFactory(clientFactory)
                                        .host(host)
                                        .build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance which connects to the given host and port
     * using the specified {@link ClientFactory}.
     *
     * @param clientFactory the {@link ClientFactory} that will manage the connections
     * @param host the host name or IP address of the Central Dogma server
     */
    static CentralDogma forHost(ClientFactory clientFactory, String host, int port) {
        return new CentralDogmaBuilder().clientFactory(clientFactory)
                                        .host(host, port)
                                        .build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance which connects to the given host at port 36462
     * using the default {@link ClientFactory}. This {@link CentralDogma} instance connects to the server
     * with TLS enabled.
     *
     * @param host the host name or IP address of the Central Dogma server
     */
    static CentralDogma forTlsHost(String host) {
        return new CentralDogmaBuilder().host(host).useTls().build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance which connects to the given host and port
     * using the default {@link ClientFactory}. This {@link CentralDogma} instance connects to the server
     * with TLS enabled.
     *
     * @param host the host name or IP address of the Central Dogma server
     * @param port the port number of the Central Dogma server
     */
    static CentralDogma forTlsHost(String host, int port) {
        return new CentralDogmaBuilder().host(host, port).useTls().build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance which connects to the given host at port 36462
     * using the specified {@link ClientFactory}. This {@link CentralDogma} instance connects to the server
     * with TLS enabled.
     *
     * @param clientFactory the {@link ClientFactory} that will manage the connections
     * @param host the host name or IP address of the Central Dogma server
     */
    static CentralDogma forTlsHost(ClientFactory clientFactory, String host) {
        return new CentralDogmaBuilder().clientFactory(clientFactory)
                                        .host(host)
                                        .useTls()
                                        .build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance which connects to the given host and port
     * using the specified {@link ClientFactory}. This {@link CentralDogma} instance connects to the server
     * with TLS enabled.
     *
     * @param clientFactory the {@link ClientFactory} that will manage the connections
     * @param host the host name or IP address of the Central Dogma server
     */
    static CentralDogma forTlsHost(ClientFactory clientFactory, String host, int port) {
        return new CentralDogmaBuilder().clientFactory(clientFactory)
                                        .host(host, port)
                                        .useTls()
                                        .build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance with the given profile names and the default
     * {@link ClientFactory}.
     *
     * @param profiles the list of the profile names, in the order of preference
     */
    static CentralDogma forProfile(String... profiles) {
        return new CentralDogmaBuilder().profile(profiles).build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance with the given profile names and the default
     * {@link ClientFactory}.
     *
     * @param profiles the list of the profile names, in the order of preference
     */
    static CentralDogma forProfile(Iterable<String> profiles) {
        return new CentralDogmaBuilder().profile(profiles).build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance with the given profile names using the specified
     * {@link ClientFactory}.
     *
     * @param clientFactory the {@link ClientFactory} that will manage the connections
     * @param profiles the list of the profile names, in the order of preference
     */
    static CentralDogma forProfile(ClientFactory clientFactory, String... profiles) {
        return new CentralDogmaBuilder().clientFactory(clientFactory)
                                        .profile(profiles)
                                        .build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance with the given profile names using the specified
     * {@link ClientFactory}.
     *
     * @param clientFactory the {@link ClientFactory} that will manage the connections
     * @param profiles the list of the profile names, in the order of preference
     */
    static CentralDogma forProfile(ClientFactory clientFactory, Iterable<String> profiles) {
        return new CentralDogmaBuilder().clientFactory(clientFactory)
                                        .profile(profiles)
                                        .build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance with the given URI and
     * the default {@link ClientFactory}.
     *
     * @deprecated Use {@link #forHost(String)} or {@link #forProfile(String...)} instead.
     *
     * @param uri the URI of the Central Dogma server. e.g. tbinary+http://example.com:36462/cd/thrift/v1
     */
    @Deprecated
    static CentralDogma newClient(String uri) {
        return new CentralDogmaBuilder().uri(uri).build();
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance with the given {@link ClientFactory} and URI.
     *
     * @deprecated Use {@link #forHost(String)} or {@link #forProfile(String...)} instead.
     *
     * @param clientFactory the {@link ClientFactory} that will manage the connections
     * @param uri the URI of the Central Dogma server. e.g. tbinary+http://example.com:36462/cd/thrift/v1
     */
    @Deprecated
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
     * Removes a project. A removed project can be unremoved using {@link #unremoveProject(String)}.
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
     * Retrieves the list of the removed projects, which can be {@linkplain #unremoveProject(String) unremoved}.
     */
    CompletableFuture<Set<String>> listRemovedProjects();

    /**
     * Creates a repository.
     */
    CompletableFuture<Void> createRepository(String projectName, String repositoryName);

    /**
     * Removes a repository. A removed repository can be unremoved using
     * {@link #unremoveRepository(String, String)}.
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
     * Retrieves the list of the removed repositories, which can be
     * {@linkplain #unremoveRepository(String, String) unremoved}.
     */
    CompletableFuture<Set<String>> listRemovedRepositories(String projectName);

    /**
     * Converts the relative revision number to the absolute revision number. e.g. {@code -1 -> 3}
     */
    CompletableFuture<Revision> normalizeRevision(String projectName, String repositoryName, Revision revision);

    /**
     * Retrieves the list of the files that match the given path pattern. A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to match <em>any</em> patterns</li>
     * </ul>
     */
    CompletableFuture<Map<String, EntryType>> listFiles(String projectName, String repositoryName,
                                                        Revision revision, String pathPattern);

    /**
     * Retrieves the file at the specified revision and path. This method is a shortcut of
     * {@code getFile(projectName, repositoryName, revision, Query.identity(path)}.
     */
    default CompletableFuture<Entry<Object>> getFile(String projectName, String repositoryName,
                                                     Revision revision, String path) {
        return getFile(projectName, repositoryName, revision, Query.identity(path));
    }

    /**
     * Queries a file at the specified revision and path with the specified {@link Query}.
     */
    <T> CompletableFuture<Entry<T>> getFile(String projectName, String repositoryName,
                                            Revision revision, Query<T> query);

    /**
     * Retrieves the files that match the path pattern. A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to match <em>any</em> patterns</li>
     * </ul>
     */
    CompletableFuture<Map<String, Entry<?>>> getFiles(String projectName, String repositoryName,
                                                      Revision revision, String pathPattern);

    /**
     * Retrieves the history of the repository between two {@link Revision}s. This method is a shortcut of
     * {@code getHistory(projectName, repositoryName, from, to, "/**")}
     */
    default CompletableFuture<List<CommitAndChanges<?>>> getHistory(
            String projectName, String repositoryName, Revision from, Revision to) {
        return getHistory(projectName, repositoryName, from, to, "/**");
    }

    /**
     * Retrieves the history of the files that match the given path pattern between two {@link Revision}s.
     * A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to match <em>any</em> patterns</li>
     * </ul>
     */
    CompletableFuture<List<CommitAndChanges<?>>> getHistory(
            String projectName, String repositoryName, Revision from, Revision to, String pathPattern);

    /**
     * Returns the diff of a file between two {@link Revision}s. This method is a shortcut of
     * {@code getDiff(projectName, repositoryName, from, to, Query.identity(path))}
     */
    default CompletableFuture<Change<?>> getDiff(String projectName, String repositoryName,
                                                 Revision from, Revision to, String path) {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        final CompletableFuture<Change<?>> diff = (CompletableFuture<Change<?>>) (CompletableFuture)
                getDiff(projectName, repositoryName, from, to, Query.identity(path));
        return diff;
    }

    /**
     * Queries a file at two different revisions and returns the diff of the two {@link Query} results.
     */
    <T> CompletableFuture<Change<T>> getDiff(String projectName, String repositoryName,
                                             Revision from, Revision to, Query<T> query);

    /**
     * Retrieves the diffs of the files that match the given path pattern between two {@link Revision}s.
     * A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to match <em>any</em> patterns</li>
     * </ul>
     */
    CompletableFuture<List<Change<?>>> getDiffs(String projectName, String repositoryName,
                                                Revision from, Revision to, String pathPattern);

    /**
     * Retrieves the preview diffs between the specified base {@link Revision} and the specified
     * {@link Change}s.
     */
    default CompletableFuture<List<Change<?>>> getPreviewDiffs(String projectName, String repositoryName,
                                                               Revision baseRevision, Change<?>... changes) {
        return getPreviewDiffs(projectName, repositoryName, baseRevision, ImmutableList.copyOf(changes));
    }

    /**
     * Retrieves the preview diffs between the specified base {@link Revision} and the specified
     * {@link Change}s.
     */
    CompletableFuture<List<Change<?>>> getPreviewDiffs(String projectName, String repositoryName,
                                                       Revision baseRevision,
                                                       Iterable<? extends Change<?>> changes);

    /**
     * Pushes the specified {@link Change}s to the repository.
     */
    default CompletableFuture<Commit> push(String projectName, String repositoryName, Revision baseRevision,
                                           Author author, String summary, Change<?>... changes) {
        return push(projectName, repositoryName, baseRevision, author, summary, ImmutableList.copyOf(changes));
    }

    /**
     * Pushes the specified {@link Change}s to the repository.
     */
    default CompletableFuture<Commit> push(String projectName, String repositoryName, Revision baseRevision,
                                           Author author, String summary,
                                           Iterable<? extends Change<?>> changes) {
        return push(projectName, repositoryName, baseRevision, author, summary, "", Markup.PLAINTEXT, changes);
    }

    /**
     * Pushes the specified {@link Change}s to the repository.
     */
    default CompletableFuture<Commit> push(String projectName, String repositoryName, Revision baseRevision,
                                           Author author, String summary, String detail, Markup markup,
                                           Change<?>... changes) {
        return push(projectName, repositoryName, baseRevision, author, summary, detail, markup,
                    ImmutableList.copyOf(changes));
    }

    /**
     * Pushes the specified {@link Change}s to the repository.
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
