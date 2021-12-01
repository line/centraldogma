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
import java.util.concurrent.Executor;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryType;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;

/**
 * Central Dogma client.
 */
public interface CentralDogma {
    /**
     * Creates a project.
     */
    CompletableFuture<Void> createProject(String projectName);

    /**
     * Removes a project. A removed project can be unremoved using {@link #unremoveProject(String)}.
     */
    CompletableFuture<Void> removeProject(String projectName);

    /**
     * Purges a project that was removed before.
     */
    CompletableFuture<Void> purgeProject(String projectName);

    /**
     * Unremoves a project.
     */
    CompletableFuture<Void> unremoveProject(String projectName);

    /**
     * Retrieves the list of the projects.
     *
     * @return a {@link Set} that contains the names of the projects
     */
    CompletableFuture<Set<String>> listProjects();

    /**
     * Retrieves the list of the removed projects, which can be {@linkplain #unremoveProject(String) unremoved}.
     *
     * @return a {@link Set} that contains the names of the removed projects
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
     * Purges a repository that was removed before.
     */
    CompletableFuture<Void> purgeRepository(String projectName, String repositoryName);

    /**
     * Unremoves a repository.
     */
    CompletableFuture<Void> unremoveRepository(String projectName, String repositoryName);

    /**
     * Retrieves the list of the repositories.
     *
     * @return a {@link Map} of repository name and {@link RepositoryInfo} pairs
     */
    CompletableFuture<Map<String, RepositoryInfo>> listRepositories(String projectName);

    /**
     * Retrieves the list of the removed repositories, which can be
     * {@linkplain #unremoveRepository(String, String) unremoved}.
     *
     * @return a {@link Set} that contains the names of the removed repositories
     */
    CompletableFuture<Set<String>> listRemovedRepositories(String projectName);

    /**
     * Converts the relative revision number to the absolute revision number. e.g. {@code -1 -> 3}
     *
     * @return the absolute {@link Revision}
     */
    CompletableFuture<Revision> normalizeRevision(String projectName, String repositoryName, Revision revision);

    /**
     * Retrieves the list of the files matched by the given path pattern. A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to specify more than one pattern. A file will be matched
     *                                     if <em>any</em> pattern matches.</li>
     * </ul>
     *
     * @return a {@link Map} of file path and type pairs
     */
    CompletableFuture<Map<String, EntryType>> listFiles(String projectName, String repositoryName,
                                                        Revision revision, String pathPattern);

    /**
     * Retrieves the file at the specified revision and path. This method is a shortcut of
     * {@code getFile(projectName, repositoryName, revision, Query.identity(path)}.
     * Consider using {@link #getFile(String, String, Revision, Query)} with {@link Query#ofText(String)} or
     * {@link Query#ofJson(String)} if you already know the file type.
     *
     * @return the {@link Entry} at the given {@code path}
     */
    default CompletableFuture<Entry<?>> getFile(String projectName, String repositoryName,
                                                Revision revision, String path) {
        @SuppressWarnings("unchecked")
        final CompletableFuture<Entry<?>> f = (CompletableFuture<Entry<?>>) (CompletableFuture<?>)
                getFile(projectName, repositoryName, revision, Query.of(QueryType.IDENTITY, path));
        return f;
    }

    /**
     * Queries a file at the specified revision and path with the specified {@link Query}.
     *
     * @return the {@link Entry} that is matched by the given {@link Query}
     */
    <T> CompletableFuture<Entry<T>> getFile(String projectName, String repositoryName,
                                            Revision revision, Query<T> query);

    /**
     * Retrieves the files matched by the path pattern. A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to specify more than one pattern. A file will be matched
     *                                     if <em>any</em> pattern matches.</li>
     * </ul>
     *
     * @return a {@link Map} of file path and {@link Entry} pairs
     */
    CompletableFuture<Map<String, Entry<?>>> getFiles(String projectName, String repositoryName,
                                                      Revision revision, String pathPattern);

    /**
     * Retrieves the merged entry of the specified {@link MergeSource}s at the specified revision.
     * Only JSON entry merge is currently supported. The JSON files are merged sequentially as specified in
     * the {@code mergeSources}.
     *
     * <p>Note that only {@link ObjectNode} is recursively merged traversing the children. Other node types are
     * simply replaced.
     *
     * @return the {@link MergedEntry} which contains the result of the merge
     */
    default CompletableFuture<MergedEntry<?>> mergeFiles(
            String projectName, String repositoryName,
            Revision revision, MergeSource... mergeSources) {
        return mergeFiles(projectName, repositoryName, revision,
                          ImmutableList.copyOf(requireNonNull(mergeSources, "mergeSources")));
    }

    /**
     * Retrieves the merged entry of the specified {@link MergeSource}s at the specified revision.
     * Only JSON entry merge is currently supported. The JSON files are merged sequentially as specified in
     * the {@code mergeSources}.
     *
     * <p>Note that only {@link ObjectNode} is recursively merged traversing the children. Other node types are
     * simply replaced.
     *
     * @return the {@link MergedEntry} which contains the result of the merge
     */
    default CompletableFuture<MergedEntry<?>> mergeFiles(
            String projectName, String repositoryName,
            Revision revision, Iterable<MergeSource> mergeSources) {
        @SuppressWarnings("unchecked")
        final CompletableFuture<MergedEntry<?>> future =
                (CompletableFuture<MergedEntry<?>>) (CompletableFuture<?>) mergeFiles(
                        projectName, repositoryName, revision, MergeQuery.ofJson(mergeSources));
        return future;
    }

    /**
     * Retrieves the merged entry of the specified {@link MergeQuery} at the specified revision.
     * Only JSON entry merge is currently supported. The JSON files are merged sequentially as specified in
     * the {@link MergeQuery}.
     *
     * <p>Note that only {@link ObjectNode} is recursively merged traversing the children. Other node types are
     * simply replaced.
     *
     * @return the {@link MergedEntry} which contains the result of the merge
     */
    <T> CompletableFuture<MergedEntry<T>> mergeFiles(String projectName, String repositoryName,
                                                     Revision revision, MergeQuery<T> mergeQuery);

    /**
     * Retrieves the history of the repository between two {@link Revision}s. This method is a shortcut of
     * {@code getHistory(projectName, repositoryName, from, to, "/**")}. Note that this method does not
     * retrieve the diffs but only metadata about the changes.
     * Use {@link #getDiff(String, String, Revision, Revision, Query)} or
     * {@link #getDiffs(String, String, Revision, Revision, String)} to retrieve the diffs.
     *
     * @return a {@link List} that contains the {@link Commit}s of the specified repository
     */
    default CompletableFuture<List<Commit>> getHistory(
            String projectName, String repositoryName, Revision from, Revision to) {
        return getHistory(projectName, repositoryName, from, to, "/**");
    }

    /**
     * Retrieves the history of the files matched by the given path pattern between two {@link Revision}s.
     * A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to specify more than one pattern. A file will be matched
     *                                     if <em>any</em> pattern matches.</li>
     * </ul>
     *
     * <p>Note that this method does not retrieve the diffs but only metadata about the changes.
     * Use {@link #getDiff(String, String, Revision, Revision, Query)} or
     * {@link #getDiffs(String, String, Revision, Revision, String)} to retrieve the diffs.
     *
     * @return a {@link List} that contains the {@link Commit}s of the files matched by the given
     *         {@code pathPattern} in the specified repository
     */
    CompletableFuture<List<Commit>> getHistory(
            String projectName, String repositoryName, Revision from, Revision to, String pathPattern);

    /**
     * Returns the diff of a file between two {@link Revision}s. This method is a shortcut of
     * {@code getDiff(projectName, repositoryName, from, to, Query.identity(path))}.
     * Consider using {@link #getDiff(String, String, Revision, Revision, Query)} with
     * {@link Query#ofText(String)} or {@link Query#ofJson(String)} if you already know the file type.
     *
     * @return the {@link Change} that contains the diff of the given {@code path} between the specified
     *         two revisions
     */
    default CompletableFuture<Change<?>> getDiff(String projectName, String repositoryName,
                                                 Revision from, Revision to, String path) {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        final CompletableFuture<Change<?>> diff = (CompletableFuture<Change<?>>) (CompletableFuture)
                getDiff(projectName, repositoryName, from, to, Query.of(QueryType.IDENTITY, path));
        return diff;
    }

    /**
     * Queries a file at two different revisions and returns the diff of the two {@link Query} results.
     *
     * @return the {@link Change} that contains the diff of the file matched by the given {@code query}
     *         between the specified two revisions
     */
    <T> CompletableFuture<Change<T>> getDiff(String projectName, String repositoryName,
                                             Revision from, Revision to, Query<T> query);

    /**
     * Retrieves the diffs of the files matched by the given path pattern between two {@link Revision}s.
     * A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to specify more than one pattern. A file will be matched
     *                                     if <em>any</em> pattern matches.</li>
     * </ul>
     *
     * @return a {@link List} of the {@link Change}s that contain the diffs between the files matched by the
     *         given {@code pathPattern} between two revisions.
     */
    CompletableFuture<List<Change<?>>> getDiffs(String projectName, String repositoryName,
                                                Revision from, Revision to, String pathPattern);

    /**
     * Retrieves the <em>preview diffs</em>, which are hypothetical diffs generated if the specified
     * {@link Change}s were successfully pushed to the specified repository. This operation is useful for
     * pre-checking if the specified {@link Change}s will be applied as expected without any conflicts.
     *
     * @return the diffs which would be committed if the specified {@link Change}s were pushed successfully
     */
    default CompletableFuture<List<Change<?>>> getPreviewDiffs(String projectName, String repositoryName,
                                                               Revision baseRevision, Change<?>... changes) {
        return getPreviewDiffs(projectName, repositoryName, baseRevision, ImmutableList.copyOf(changes));
    }

    /**
     * Retrieves the <em>preview diffs</em>, which are hypothetical diffs generated if the specified
     * {@link Change}s were successfully pushed to the specified repository. This operation is useful for
     * pre-checking if the specified {@link Change}s will be applied as expected without any conflicts.
     *
     * @return the diffs which would be committed if the specified {@link Change}s were pushed successfully
     */
    CompletableFuture<List<Change<?>>> getPreviewDiffs(String projectName, String repositoryName,
                                                       Revision baseRevision,
                                                       Iterable<? extends Change<?>> changes);

    /**
     * Pushes the specified {@link Change}s to the repository.
     *
     * @return the {@link PushResult} which tells the {@link Revision} and timestamp of the new {@link Commit}
     */
    default CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                               String summary, Change<?>... changes) {
        return push(projectName, repositoryName, baseRevision, summary,
                    ImmutableList.copyOf(requireNonNull(changes, "changes")));
    }

    /**
     * Pushes the specified {@link Change}s to the repository.
     *
     * @return the {@link PushResult} which tells the {@link Revision} and timestamp of the new {@link Commit}
     */
    default CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                               String summary, Iterable<? extends Change<?>> changes) {
        return push(projectName, repositoryName, baseRevision, summary, "", Markup.PLAINTEXT, changes);
    }

    /**
     * Pushes the specified {@link Change}s to the repository.
     *
     * @return the {@link PushResult} which tells the {@link Revision} and timestamp of the new {@link Commit}
     */
    default CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                               String summary, String detail, Markup markup,
                                               Change<?>... changes) {
        return push(projectName, repositoryName, baseRevision, summary, detail, markup,
                    ImmutableList.copyOf(requireNonNull(changes, "changes")));
    }

    /**
     * Pushes the specified {@link Change}s to the repository.
     *
     * @return the {@link PushResult} which tells the {@link Revision} and timestamp of the new {@link Commit}
     */
    CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                       String summary, String detail, Markup markup,
                                       Iterable<? extends Change<?>> changes);

    /**
     * Pushes the specified {@link Change}s to the repository.
     *
     * @return the {@link PushResult} which tells the {@link Revision} and timestamp of the new {@link Commit}
     *
     * @deprecated Use {@link #push(String, String, Revision, String, Change...)}.
     */
    @Deprecated
    default CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                               Author author, String summary, Change<?>... changes) {
        return push(projectName, repositoryName, baseRevision, author, summary, ImmutableList.copyOf(changes));
    }

    /**
     * Pushes the specified {@link Change}s to the repository.
     *
     * @return the {@link PushResult} which tells the {@link Revision} and timestamp of the new {@link Commit}
     *
     * @deprecated Use {@link #push(String, String, Revision, String, Iterable)}.
     */
    @Deprecated
    default CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                               Author author, String summary,
                                               Iterable<? extends Change<?>> changes) {
        return push(projectName, repositoryName, baseRevision, author, summary, "", Markup.PLAINTEXT, changes);
    }

    /**
     * Pushes the specified {@link Change}s to the repository.
     *
     * @return the {@link PushResult} which tells the {@link Revision} and timestamp of the new {@link Commit}
     *
     * @deprecated Use {@link #push(String, String, Revision, String, String, Markup, Change...)}.
     */
    @Deprecated
    default CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                               Author author, String summary, String detail, Markup markup,
                                               Change<?>... changes) {
        return push(projectName, repositoryName, baseRevision, author, summary, detail, markup,
                    ImmutableList.copyOf(changes));
    }

    /**
     * Pushes the specified {@link Change}s to the repository.
     *
     * @return the {@link PushResult} which tells the {@link Revision} and timestamp of the new {@link Commit}
     *
     * @deprecated Use {@link #push(String, String, Revision, String, String, Markup, Iterable)}.
     */
    @Deprecated
    CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                       Author author, String summary, String detail, Markup markup,
                                       Iterable<? extends Change<?>> changes);

    /**
     * Waits for the files matched by the specified {@code pathPattern} to be changed since the specified
     * {@code lastKnownRevision}. If no changes were made within 1 minute, the returned
     * {@link CompletableFuture} will be completed with {@code null}.
     *
     * @return the latest known {@link Revision} which contains the changes for the matched files.
     *         {@code null} if the files were not changed for 1 minute since the invocation of this method.
     */
    default CompletableFuture<Revision> watchRepository(String projectName, String repositoryName,
                                                        Revision lastKnownRevision, String pathPattern) {
        return watchRepository(projectName, repositoryName, lastKnownRevision, pathPattern,
                               WatchOptions.defaultOptions());
    }

    /**
     * Waits for the files matched by the specified {@code pathPattern} to be changed since the specified
     * {@code lastKnownRevision}.  If no changes were made within the specified {@code timeoutMillis}, the
     * returned {@link CompletableFuture} will be completed with {@code null}. It is recommended to specify
     * the largest {@code timeoutMillis} allowed by the server. If unsure, use
     * {@link #watchRepository(String, String, Revision, String)}.
     *
     * @return the latest known {@link Revision} which contains the changes for the matched files.
     *         {@code null} if the files were not changed for {@code timeoutMillis} milliseconds
     *         since the invocation of this method.
     *
     */
    default CompletableFuture<Revision> watchRepository(String projectName, String repositoryName,
                                                Revision lastKnownRevision, String pathPattern,
                                                long timeoutMillis) {
        return watchRepository(projectName, repositoryName, lastKnownRevision, pathPattern,
                               WatchOptions.builder().timeoutMillis(timeoutMillis).build());
    }

    /**
     * Waits for the files matched by the specified {@code pathPattern} to be changed since the specified
     * {@code lastKnownRevision}. If the files don't exist and {@link WatchOptions#errorOnEntryNotFound}
     * is ture, stops to wait files and throw {@link EntryNotFoundException} as the cause.
     * If {@link WatchOptions#errorOnEntryNotFound} is false and no changes were made
     * within the specified {@link WatchOptions#timeoutMillis}, the returned {@link CompletableFuture}
     * will be completed with {@code null}.
     *
     * <p>Note: Legacy client only support {@link WatchOptions#timeoutMillis}</p>
     *
     * @return the {@link Entry} which contains the latest known {@link Query} result.
     *         {@link EntryNotFoundException} if the file does not exist
     *         and {@link WatchOptions#errorOnEntryNotFound} is ture.
     *         {@code null} if the file was not changed for {@link WatchOptions#timeoutMillis} milliseconds
     *         since the invocation of this method.
     */
    CompletableFuture<Revision> watchRepository(String projectName, String repositoryName,
                                                Revision lastKnownRevision, String pathPattern,
                                                WatchOptions watchOptions);

    /**
     * Waits for the file matched by the specified {@link Query} to be changed since the specified
     * {@code lastKnownRevision}. If no changes were made within 1 minute, the returned
     * {@link CompletableFuture} will be completed with {@code null}.
     *
     * @return the {@link Entry} which contains the latest known {@link Query} result.
     *         {@code null} if the file was not changed for 1 minute since the invocation of this method.
     */
    default <T> CompletableFuture<Entry<T>> watchFile(String projectName, String repositoryName,
                                                      Revision lastKnownRevision, Query<T> query) {
        return watchFile(projectName, repositoryName, lastKnownRevision, query, WatchOptions.defaultOptions());
    }

    /**
     * Waits for the file matched by the specified {@link Query} to be changed since the specified
     * {@code lastKnownRevision}. If no changes were made within the specified {@code timeoutMillis}, the
     * returned {@link CompletableFuture} will be completed with {@code null}. It is recommended to specify
     * the largest {@code timeoutMillis} allowed by the server. If unsure, use
     * {@link #watchFile(String, String, Revision, Query)}.
     *
     * @return the {@link Entry} which contains the latest known {@link Query} result.
     *         {@code null} if the file was not changed for {@code timeoutMillis} milliseconds
     *         since the invocation of this method.
     */
    default <T> CompletableFuture<Entry<T>> watchFile(String projectName, String repositoryName,
                                              Revision lastKnownRevision, Query<T> query,
                                              long timeoutMillis) {
        return watchFile(projectName, repositoryName, lastKnownRevision, query,
                         WatchOptions.builder().timeoutMillis(timeoutMillis).build());
    }

    /**
     * Waits for the file matched by the specified {@link Query} to be changed since the specified
     * {@code lastKnownRevision}. If the file does not exist and {@link WatchOptions#errorOnEntryNotFound}
     * is ture, stops to wait a file and throw {@link EntryNotFoundException} as the cause.
     * If {@link WatchOptions#errorOnEntryNotFound} is false and no changes were made
     * within the specified {@link WatchOptions#timeoutMillis}, the returned {@link CompletableFuture}
     * will be completed with {@code null}.
     *
     * <p>Note: Legacy client only support {@link WatchOptions#timeoutMillis}</p>
     *
     * @return the {@link Entry} which contains the latest known {@link Query} result.
     *         {@link EntryNotFoundException} if the file does not exist
     *         and {@link WatchOptions#errorOnEntryNotFound} is ture.
     *         {@code null} if the file was not changed for {@link WatchOptions#timeoutMillis} milliseconds
     *         since the invocation of this method.
     */
    <T> CompletableFuture<Entry<T>> watchFile(String projectName, String repositoryName,
                                              Revision lastKnownRevision, Query<T> query,
                                              WatchOptions watchOptions);

    /**
     * Returns a {@link Watcher} which notifies its listeners when the result of the
     * given {@link Query} becomes available or changes. e.g:
     * <pre>{@code
     * Watcher<JsonNode> watcher = client.fileWatcher("foo", "bar", Query.ofJson("/baz.json"));
     *
     * watcher.watch((revision, content) -> {
     *     assert content instanceof JsonNode;
     *     ...
     * });}</pre>
     */
    default <T> Watcher<T> fileWatcher(String projectName, String repositoryName, Query<T> query) {
        return fileWatcher(projectName, repositoryName, query, Function.identity(), null, null);
    }

    /**
     * Returns a {@link Watcher} which notifies its listeners after applying the specified
     * {@link Function} when the result of the given {@link Query} becomes available or changes. e.g:
     * <pre>{@code
     * Watcher<MyType> watcher = client.fileWatcher(
     *         "foo", "bar", Query.ofJson("/baz.json"),
     *         content -> new ObjectMapper().treeToValue(content, MyType.class));
     *
     * watcher.watch((revision, myValue) -> {
     *     assert myValue instanceof MyType;
     *     ...
     * });}</pre>
     *
     * <p>Note that {@link Function} by default is executed by a blocking task executor so that you can
     * safely call a blocking operation.
     */
    default <T, U> Watcher<U> fileWatcher(String projectName, String repositoryName,
                                  Query<T> query, Function<? super T, ? extends U> function) {
        return fileWatcher(projectName, repositoryName, query, function, null, null);
    }

    /**
     * Returns a {@link Watcher} which notifies its listeners after applying the specified
     * {@link Function} when the result of the given {@link Query} becomes available or changes. e.g:
     * <pre>{@code
     * Watcher<MyType> watcher = client.fileWatcher(
     *         "foo", "bar", Query.ofJson("/baz.json"),
     *         content -> new ObjectMapper().treeToValue(content, MyType.class), executor);
     *
     * watcher.watch((revision, myValue) -> {
     *     assert myValue instanceof MyType;
     *     ...
     * });}</pre>
     *
     * @param executor the {@link Executor} that executes the {@link Function}
     */
    default <T, U> Watcher<U> fileWatcher(String projectName, String repositoryName,
                                  Query<T> query, Function<? super T, ? extends U> function, Executor executor) {
        return fileWatcher(projectName, repositoryName, query, function, executor, null);
    }

    /**
     * Returns a {@link Watcher} which notifies its listeners after applying the specified
     * {@link Function} when the result of the given {@link Query} becomes available or changes. e.g:
     * <pre>{@code
     * Watcher<MyType> watcher = client.fileWatcher(
     *         "foo", "bar", Query.ofJson("/baz.json"),
     *         content -> new ObjectMapper().treeToValue(content, MyType.class), executor,
     *         WatchOptions.builder().timeoutMillis(60000).errorOnEntryNotFound(true).build());
     *
     * watcher.watch((revision, myValue) -> {
     *     assert myValue instanceof MyType;
     *     ...
     * });}</pre>
     *
     * @param executor the {@link Executor} that executes the {@link Function}.
     *        if {@code executor} is null, {@link Function} executed by a blocking task executor.
     * @param watchOptions the {@link WatchOptions} that is options on waiting the matched files.
     *        if {@code watchOptions} is null, use {@link WatchOptions#defaultOptions()}.
     *        if {@link WatchOptions#errorOnEntryNotFound} is true and entry doesn't exist,
     *        {@link Watcher#initialValueFuture()} will be completed with {@link EntryNotFoundException}.
     *        {@link WatchOptions#timeoutMillis} is the waiting time about the matched files have a change.
     *        If any no changes during {@link WatchOptions#timeoutMillis}, re-wait the matched files.
     *        <p>Note: Legacy client only support {@link WatchOptions#timeoutMillis}</p>
     */

    <T, U> Watcher<U> fileWatcher(String projectName, String repositoryName,
                                  Query<T> query,
                                  Function<? super T, ? extends U> function,
                                  @Nullable Executor executor,
                                  @Nullable WatchOptions watchOptions);

    /**
     * Returns a {@link Watcher} which notifies its listeners when the specified repository has a new commit
     * that contains the changes for the files matched by the given {@code pathPattern}. e.g:
     * <pre>{@code
     * Watcher<Revision> watcher = client.repositoryWatcher("foo", "bar", "/*.json");
     *
     * watcher.watch(revision -> {
     *     ...
     * });}</pre>
     */
    default Watcher<Revision> repositoryWatcher(String projectName, String repositoryName, String pathPattern) {
        return repositoryWatcher(projectName, repositoryName, pathPattern, Function.identity(), null, null);
    }

    /**
     * Returns a {@link Watcher} which notifies its listeners when the specified repository has a new commit
     * that contains the changes for the files matched by the given {@code pathPattern}. e.g:
     * <pre>{@code
     * Watcher<Map<String, Entry<?>> watcher = client.repositoryWatcher(
     *         "foo", "bar", "/*.json", revision -> client.getFiles("foo", "bar", revision, "/*.json").join());
     *
     * watcher.watch((revision, contents) -> {
     *     ...
     * });}</pre>
     * {@link Function} by default is executed by a blocking task executor so that you can safely call a
     * blocking operation.
     *
     * <p>Note that you may get {@link RevisionNotFoundException} during the {@code getFiles()} call and
     * may have to retry in the above example due to
     * <a href="https://github.com/line/centraldogma/issues/40">a known issue</a>.
     */
    default <T> Watcher<T> repositoryWatcher(String projectName, String repositoryName, String pathPattern,
                                     Function<Revision, ? extends T> function){
        return repositoryWatcher(projectName, repositoryName, pathPattern, function, null, null);
    }

    /**
     * Returns a {@link Watcher} which notifies its listeners when the specified repository has a new commit
     * that contains the changes for the files matched by the given {@code pathPattern}. e.g:
     * <pre>{@code
     * Watcher<Map<String, Entry<?>> watcher = client.repositoryWatcher(
     *         "foo", "bar", "/*.json",
     *         revision -> client.getFiles("foo", "bar", revision, "/*.json").join(), executor);
     *
     * watcher.watch((revision, contents) -> {
     *     ...
     * });}</pre>
     * Note that you may get {@link RevisionNotFoundException} during the {@code getFiles()} call and
     * may have to retry in the above example due to
     * <a href="https://github.com/line/centraldogma/issues/40">a known issue</a>.
     *
     * @param executor the {@link Executor} that executes the {@link Function}
     */
    default <T> Watcher<T> repositoryWatcher(String projectName, String repositoryName, String pathPattern,
                                     Function<Revision, ? extends T> function, Executor executor) {
        return repositoryWatcher(projectName, repositoryName, pathPattern, function, executor, null);
    }

    /**
     * Returns a {@link Watcher} which notifies its listeners when the specified repository has a new commit
     * that contains the changes for the files matched by the given {@code pathPattern}. e.g:
     * <pre>{@code
     * Watcher<Map<String, Entry<?>> watcher = client.repositoryWatcher(
     *         "foo", "bar", "/*.json",
     *         revision -> client.getFiles("foo", "bar", revision, "/*.json").join(), executor,
     *         WatchOptions.builder().timeoutMillis(60000).errorOnEntryNotFound(true).build());
     *
     * watcher.watch((revision, contents) -> {
     *     ...
     * });}</pre>
     * Note that you may get {@link RevisionNotFoundException} during the {@code getFiles()} call and
     * may have to retry in the above example due to
     * <a href="https://github.com/line/centraldogma/issues/40">a known issue</a>.
     *
     * @param executor the {@link Executor} that executes the {@link Function}.
     *        if {@code executor} is null, {@link Function} executed by a blocking task executor.
     * @param watchOptions the {@link WatchOptions} that is options on waiting the matched files.
     *        if {@code watchOptions} is null, use {@link WatchOptions#defaultOptions()}.
     *        if {@link WatchOptions#errorOnEntryNotFound} is true and entries don't exist,
     *        {@link Watcher#initialValueFuture()} will be completed with {@link EntryNotFoundException}.
     *        {@link WatchOptions#timeoutMillis} is the waiting time about the matched files have a change.
     *        If any no changes during {@link WatchOptions#timeoutMillis}, re-wait the matched files.
     *        <p>Note: Legacy client only support {@link WatchOptions#timeoutMillis}</p>
     */

    <T> Watcher<T> repositoryWatcher(String projectName, String repositoryName, String pathPattern,
                                     Function<Revision, ? extends T> function,
                                     @Nullable Executor executor,
                                     @Nullable WatchOptions watchOptions);
}
