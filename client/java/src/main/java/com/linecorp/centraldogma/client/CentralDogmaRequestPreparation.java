/*
 * Copyright 2021 LINE Corporation
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryType;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send requests to the Central Dogma repository.
 */
public final class CentralDogmaRequestPreparation {

    private final CentralDogma centralDogma;
    private final String projectName;
    private final String repositoryName;
    @Nullable
    private ScheduledExecutorService blockingTaskExecutor;

    CentralDogmaRequestPreparation(CentralDogma centralDogma, String projectName, String repositoryName,
                                   @Nullable ScheduledExecutorService blockingTaskExecutor) {
        this.centralDogma = centralDogma;
        this.projectName = projectName;
        this.repositoryName = repositoryName;
        this.blockingTaskExecutor = blockingTaskExecutor;
    }

    CentralDogma centralDogma() {
        return centralDogma;
    }

    String projectName() {
        return projectName;
    }

    String repositoryName() {
        return repositoryName;
    }

    /**
     * Converts the relative revision number to the absolute revision number. e.g. {@code -1 -> 3}
     *
     * @return the absolute {@link Revision}
     */
    public CompletableFuture<Revision> normalize(Revision revision) {
        requireNonNull(revision, "revision");
        return centralDogma.normalizeRevision(projectName, repositoryName, revision);
    }

    /**
     * Returns a new {@link CentralDogmaFileRequest} that is used to send
     * {@link CentralDogma#getFile(String, String, Revision, Query)} request to the Central Dogma repository.
     */
    public CentralDogmaFileRequest<?> file(String path) {
        requireNonNull(path, "path");
        return file(Query.of(QueryType.IDENTITY, path));
    }

    /**
     * Returns a new {@link CentralDogmaFileRequest} that is used to send
     * {@link CentralDogma#getFile(String, String, Revision, Query)} request to the Central Dogma repository.
     */
    public <T> CentralDogmaFileRequest<T> file(Query<T> query) {
        requireNonNull(query, "query");
        return new CentralDogmaFileRequest<>(this, query);
    }

    /**
     * Returns a new {@link CentralDogmaFilesRequest} that is used to send
     * {@link CentralDogma#getFiles(String, String, Revision, String)} and
     * {@link CentralDogma#listFiles(String, String, Revision, String)} requests to the
     * Central Dogma repository.
     * A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to specify more than one pattern. A file will be matched
     *                                     if <em>any</em> pattern matches.</li>
     * </ul>
     */
    public CentralDogmaFilesRequest files(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        return new CentralDogmaFilesRequest(this, pathPattern);
    }

    /**
     * Returns a new {@link CentralDogmaMergingFilesRequest} that is used to send
     * {@link CentralDogma#mergeFiles(String, String, Revision, MergeQuery)} request to the
     * Central Dogma repository.
     */
    public CentralDogmaMergingFilesRequest<?> mergingFiles(MergeSource... mergeSources) {
        requireNonNull(mergeSources, "mergeSources");
        return mergingFiles(ImmutableList.copyOf(mergeSources));
    }

    /**
     * Returns a new {@link CentralDogmaMergingFilesRequest} that is used to send
     * {@link CentralDogma#mergeFiles(String, String, Revision, MergeQuery)} request to the
     * Central Dogma repository.
     */
    public CentralDogmaMergingFilesRequest<?> mergingFiles(Iterable<MergeSource> mergeSources) {
        requireNonNull(mergeSources, "mergeSources");
        return mergingFiles(MergeQuery.ofJson(mergeSources));
    }

    /**
     * Returns a new {@link CentralDogmaMergingFilesRequest} that is used to send
     * {@link CentralDogma#mergeFiles(String, String, Revision, MergeQuery)} request to the
     * Central Dogma repository.
     */
    public <T> CentralDogmaMergingFilesRequest<T> mergingFiles(MergeQuery<T> mergeQuery) {
        requireNonNull(mergeQuery, "mergeQuery");
        return new CentralDogmaMergingFilesRequest<>(this, mergeQuery);
    }

    /**
     * Returns a new {@link CentralDogmaHistoryRequest} that is used to send
     * {@link CentralDogma#getHistory(String, String, Revision, Revision, String)} request to the
     * Central Dogma repository.
     */
    public CentralDogmaHistoryRequest history() {
        return new CentralDogmaHistoryRequest(this, "/**");
    }

    /**
     * Returns a new {@link CentralDogmaHistoryRequest} that is used to send
     * {@link CentralDogma#getHistory(String, String, Revision, Revision, String)} request to the
     * Central Dogma repository.
     * A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to specify more than one pattern. A file will be matched
     *                                     if <em>any</em> pattern matches.</li>
     * </ul>
     */
    public CentralDogmaHistoryRequest history(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        return new CentralDogmaHistoryRequest(this, pathPattern);
    }

    /**
     * Returns a new {@link CentralDogmaDiffRequest} that is used to send
     * {@link CentralDogma#getDiff(String, String, Revision, Revision, Query)} request to the
     * Central Dogma repository.
     */
    public CentralDogmaDiffRequest<?> diff(String path) {
        requireNonNull(path, "path");
        return diff(Query.of(QueryType.IDENTITY, path));
    }

    /**
     * Returns a new {@link CentralDogmaDiffRequest} that is used to send
     * {@link CentralDogma#getDiff(String, String, Revision, Revision, Query)} request to the
     * Central Dogma repository.
     */
    public <T> CentralDogmaDiffRequest<T> diff(Query<T> query) {
        requireNonNull(query, "query");
        return new CentralDogmaDiffRequest<>(this, query);
    }

    /**
     * Returns a new {@link CentralDogmaDiffsRequest} that is used to send
     * {@link CentralDogma#getDiffs(String, String, Revision, Revision, String)} request to the
     * Central Dogma repository.
     * A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to specify more than one pattern. A file will be matched
     *                                     if <em>any</em> pattern matches.</li>
     * </ul>
     */
    public CentralDogmaDiffsRequest diffs(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        return new CentralDogmaDiffsRequest(this, pathPattern);
    }

    /**
     * Returns a new {@link CentralDogmaPreviewDiffsRequest} that is used to send
     * {@link CentralDogma#getPreviewDiffs(String, String, Revision, Iterable)} request to the
     * Central Dogma repository.
     */
    public CentralDogmaPreviewDiffsRequest previewDiffs(Change<?>... changes) {
        requireNonNull(changes, "changes");
        return new CentralDogmaPreviewDiffsRequest(this, ImmutableList.copyOf(changes));
    }

    /**
     * Returns a new {@link CentralDogmaPreviewDiffsRequest} that is used to send
     * {@link CentralDogma#getPreviewDiffs(String, String, Revision, Iterable)} request to the
     * Central Dogma repository.
     */
    public CentralDogmaPreviewDiffsRequest previewDiffs(Iterable<? extends Change<?>> changes) {
        requireNonNull(changes, "changes");
        return new CentralDogmaPreviewDiffsRequest(this, changes);
    }

    /**
     * Returns a new {@link CentralDogmaCommitRequest} that is used to send
     * {@link CentralDogma#push(String, String, Revision, String, String, Markup, Iterable)} request to the
     * Central Dogma repository.
     */
    public CentralDogmaCommitRequest commit(String summary, Change<?>... changes) {
        requireNonNull(changes, "changes");
        return new CentralDogmaCommitRequest(this, summary, ImmutableList.copyOf(changes));
    }

    /**
     * Returns a new {@link CentralDogmaCommitRequest} that is used to send
     * {@link CentralDogma#push(String, String, Revision, String, String, Markup, Iterable)} request to the
     * Central Dogma repository.
     */
    public CentralDogmaCommitRequest commit(String summary, Iterable<? extends Change<?>> changes) {
        requireNonNull(changes, "changes");
        return new CentralDogmaCommitRequest(this, summary, changes);
    }

    /**
     * Returns a new {@link CentralDogmaWatchingFileRequest} that is used to send
     * {@link CentralDogma#watchFile(String, String, Revision, Query, long)} request to
     * the Central Dogma repository or create a new
     * {@link CentralDogma#fileWatcher(String, String, Query, Function, Executor)}.
     */
    public CentralDogmaWatchingFileRequest<?> watchingFile(String path) {
        requireNonNull(path, "path");
        return watchingFile(Query.of(QueryType.IDENTITY, path));
    }

    /**
     * Returns a new {@link CentralDogmaWatchingFileRequest} that is used to send
     * {@link CentralDogma#watchFile(String, String, Revision, Query, long)} request to
     * the Central Dogma repository or create a new
     * {@link CentralDogma#fileWatcher(String, String, Query, Function, Executor)}.
     */
    public <T> CentralDogmaWatchingFileRequest<T> watchingFile(Query<T> query) {
        requireNonNull(query, "query");
        return new CentralDogmaWatchingFileRequest<>(this, query, Function.identity(), blockingTaskExecutor);
    }

    /**
     * Returns a new {@link CentralDogmaWatchingFilesRequest} that is used to send
     * {@link CentralDogma#watchRepository(String, String, Revision, String, long)} request to the
     * Central Dogma repository or create
     * a new {@link CentralDogma#repositoryWatcher(String, String, String, Function, Executor)}.
     * A path pattern is a variant of glob:
     * <ul>
     *   <li>{@code "/**"} - find all files recursively</li>
     *   <li>{@code "*.json"} - find all JSON files recursively</li>
     *   <li>{@code "/foo/*.json"} - find all JSON files under the directory {@code /foo}</li>
     *   <li><code>"/&#42;/foo.txt"</code> - find all files named {@code foo.txt} at the second depth level</li>
     *   <li>{@code "*.json,/bar/*.txt"} - use comma to specify more than one pattern. A file will be matched
     *                                     if <em>any</em> pattern matches.</li>
     * </ul>
     */
    public CentralDogmaWatchingFilesRequest<Revision> watchingFiles(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        return new CentralDogmaWatchingFilesRequest<>(this, pathPattern,
                                                      Function.identity(), blockingTaskExecutor);
    }
}
