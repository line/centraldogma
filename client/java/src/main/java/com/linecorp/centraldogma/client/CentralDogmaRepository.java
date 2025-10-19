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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import org.jspecify.annotations.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryType;
import com.linecorp.centraldogma.common.Revision;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Prepares to send requests to the Central Dogma repository.
 */
public final class CentralDogmaRepository {

    private final CentralDogma centralDogma;
    private final String projectName;
    private final String repositoryName;
    private final ScheduledExecutorService blockingTaskExecutor;
    @Nullable
    private final MeterRegistry meterRegistry;

    CentralDogmaRepository(CentralDogma centralDogma, String projectName, String repositoryName,
                           ScheduledExecutorService blockingTaskExecutor,
                           @Nullable MeterRegistry meterRegistry) {
        this.centralDogma = centralDogma;
        this.projectName = projectName;
        this.repositoryName = repositoryName;
        this.blockingTaskExecutor = blockingTaskExecutor;
        this.meterRegistry = meterRegistry;
    }

    CentralDogma centralDogma() {
        return centralDogma;
    }

    /**
     * Returns the name of the project.
     */
    public String projectName() {
        return projectName;
    }

    /**
     * Returns the name of the repository.
     */
    public String repositoryName() {
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
     * Returns a new {@link FileRequest} that is used to retrieve the file in the Central Dogma repository.
     * Call {@link FileRequest#get(Revision)} to perform the same operation as
     * {@link CentralDogma#getFile(String, String, Revision, Query)}.
     */
    public FileRequest<?> file(String path) {
        requireNonNull(path, "path");
        return file(Query.of(QueryType.IDENTITY, path));
    }

    /**
     * Returns a new {@link FileRequest} that is used to retrieve the file in the Central Dogma repository.
     * Call {@link FileRequest#get(Revision)} to perform the same operation as
     * {@link CentralDogma#getFile(String, String, Revision, Query)}.
     */
    public <T> FileRequest<T> file(Query<T> query) {
        requireNonNull(query, "query");
        return new FileRequest<>(this, query);
    }

    /**
     * Returns a new {@link FilesRequest} that is used to retrieve or list files in the
     * Central Dogma repository.
     * Call {@link FilesRequest#get(Revision)} or {@link FilesRequest#list(Revision)} for those operation.
     */
    public FilesRequest file(PathPattern pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        return new FilesRequest(this, pathPattern);
    }

    /**
     * Returns a new {@link MergeRequest} that is used to retrieve the merged file in the
     * Central Dogma repository.
     * Call {@link MergeRequest#get(Revision)} to perform the same operation as
     * {@link CentralDogma#mergeFiles(String, String, Revision, MergeQuery)}.
     */
    public MergeRequest<?> merge(MergeSource... mergeSources) {
        requireNonNull(mergeSources, "mergeSources");
        return merge(ImmutableList.copyOf(mergeSources));
    }

    /**
     * Returns a new {@link MergeRequest} that is used to retrieve the merged file in the
     * Central Dogma repository.
     * Call {@link MergeRequest#get(Revision)} to perform the same operation as
     * {@link CentralDogma#mergeFiles(String, String, Revision, MergeQuery)}.
     */
    public MergeRequest<?> merge(Iterable<MergeSource> mergeSources) {
        requireNonNull(mergeSources, "mergeSources");
        return merge(MergeQuery.ofJson(mergeSources));
    }

    /**
     * Returns a new {@link MergeRequest} that is used to retrieve the merged file in the
     * Central Dogma repository.
     * Call {@link MergeRequest#get(Revision)} to perform the same operation as
     * {@link CentralDogma#mergeFiles(String, String, Revision, MergeQuery)}.
     */
    public <T> MergeRequest<T> merge(MergeQuery<T> mergeQuery) {
        requireNonNull(mergeQuery, "mergeQuery");
        return new MergeRequest<>(this, mergeQuery);
    }

    /**
     * Returns a new {@link HistoryRequest} that is used to retrieve the history of all files in the
     * Central Dogma repository.
     * Call {@link HistoryRequest#get(Revision, Revision)} to perform the same operation as
     * {@link CentralDogma#getHistory(String, String, Revision, Revision, PathPattern, int)}.
     */
    public HistoryRequest history() {
        return history(PathPattern.all());
    }

    /**
     * Returns a new {@link HistoryRequest} that is used to retrieve the history of files in the
     * Central Dogma repository.
     * Call {@link HistoryRequest#get(Revision, Revision)} to perform the same operation as
     * {@link CentralDogma#getHistory(String, String, Revision, Revision, PathPattern, int)}.
     */
    public HistoryRequest history(PathPattern pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        return new HistoryRequest(this, pathPattern);
    }

    /**
     * Returns a new {@link DiffRequest} that is used to retrieve the diff of the file in the
     * Central Dogma repository.
     * Call {@link DiffRequest#get(Revision, Revision)} to perform the same operation as
     * {@link CentralDogma#getDiff(String, String, Revision, Revision, Query)}.
     */
    public DiffRequest<?> diff(String path) {
        requireNonNull(path, "path");
        return diff(Query.of(QueryType.IDENTITY, path));
    }

    /**
     * Returns a new {@link DiffRequest} that is used to retrieve the diff of the file in the
     * Central Dogma repository.
     * Call {@link DiffRequest#get(Revision, Revision)} to perform the same operation as
     * {@link CentralDogma#getDiff(String, String, Revision, Revision, Query)}.
     */
    public <T> DiffRequest<T> diff(Query<T> query) {
        requireNonNull(query, "query");
        return new DiffRequest<>(this, query);
    }

    /**
     * Returns a new {@link DiffFilesRequest} that is used to retrieve the diff of files in the
     * Central Dogma repository.
     * Call {@link DiffFilesRequest#get(Revision, Revision)} to perform the same operation as
     * {@link CentralDogma#getDiff(String, String, Revision, Revision, PathPattern)}.
     */
    public DiffFilesRequest diff(PathPattern pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        return new DiffFilesRequest(this, pathPattern);
    }

    /**
     * Returns a new {@link PreviewDiffRequest} that is used to retrieve the preview diff of files in the
     * Central Dogma repository.
     * Call {@link PreviewDiffRequest#get(Revision)} to perform the same operation as
     * {@link CentralDogma#getPreviewDiffs(String, String, Revision, Iterable)}.
     */
    public PreviewDiffRequest diff(Change<?>... changes) {
        requireNonNull(changes, "changes");
        return new PreviewDiffRequest(this, ImmutableList.copyOf(changes));
    }

    /**
     * Returns a new {@link PreviewDiffRequest} that is used to retrieve the preview diff of files in the
     * Central Dogma repository.
     * Call {@link PreviewDiffRequest#get(Revision)} to perform the same operation as
     * {@link CentralDogma#getPreviewDiffs(String, String, Revision, Iterable)}.
     */
    public PreviewDiffRequest diff(Iterable<? extends Change<?>> changes) {
        requireNonNull(changes, "changes");
        return new PreviewDiffRequest(this, changes);
    }

    /**
     * Returns a new {@link CommitRequest} that is used to push the {@link Change}s to the
     * Central Dogma repository.
     * Call {@link CommitRequest#push(Revision)} to perform the same operation as
     * {@link CentralDogma#push(String, String, Revision, String, String, Markup, Iterable)}.
     */
    public CommitRequest commit(String summary, Change<?>... changes) {
        requireNonNull(changes, "changes");
        return commit(summary, ImmutableList.copyOf(changes));
    }

    /**
     * Returns a new {@link CommitRequest} that is used to push the {@link Change}s to the
     * Central Dogma repository.
     * Call {@link CommitRequest#push(Revision)} to perform the same operation as
     * {@link CentralDogma#push(String, String, Revision, String, String, Markup, Iterable)}.
     */
    public CommitRequest commit(String summary, Iterable<? extends Change<?>> changes) {
        requireNonNull(summary, "summary");
        requireNonNull(changes, "changes");
        return new CommitRequest(this, summary, changes);
    }

    /**
     * Returns a new {@link WatchRequest} that is used to watch the file in the
     * Central Dogma repository.
     * Call {@link WatchRequest#start(Revision)} to perform the same operation as
     * {@link CentralDogma#watchFile(String, String, Revision, Query, long, boolean)}.
     */
    public WatchRequest<?> watch(String path) {
        requireNonNull(path, "path");
        return watch(Query.of(QueryType.IDENTITY, path));
    }

    /**
     * Returns a new {@link WatchRequest} that is used to watch the file in the
     * Central Dogma repository.
     * Call {@link WatchRequest#start(Revision)} to perform the same operation as
     * {@link CentralDogma#watchFile(String, String, Revision, Query, long, boolean)}.
     */
    public <T> WatchRequest<T> watch(Query<T> query) {
        requireNonNull(query, "query");
        return new WatchRequest<>(this, query);
    }

    /**
     * Returns a new {@link WatchFilesRequest} that is used to watch the files in the
     * Central Dogma repository.
     * Call {@link WatchFilesRequest#start(Revision)} to perform the same operation as
     * {@link CentralDogma#watchRepository(String, String, Revision, PathPattern, long, boolean)}.
     */
    public WatchFilesRequest watch(PathPattern pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        return new WatchFilesRequest(this, pathPattern);
    }

    /**
     * Returns a new {@link WatcherRequest} that is used to create a {@link Watcher}.
     */
    public <T> WatcherRequest<T> watcher(Query<T> query) {
        requireNonNull(query, "query");
        return new WatcherRequest<>(this, query, blockingTaskExecutor, meterRegistry);
    }

    /**
     * Returns a new {@link WatcherRequest} that is used to create a {@link Watcher}.
     */
    public WatcherRequest<Revision> watcher(PathPattern pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        return new WatcherRequest<>(this, pathPattern, blockingTaskExecutor, meterRegistry);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CentralDogmaRepository)) {
            return false;
        }
        final CentralDogmaRepository that = (CentralDogmaRepository) o;
        return centralDogma == that.centralDogma &&
               projectName.equals(that.projectName) && repositoryName.equals(that.repositoryName) &&
               blockingTaskExecutor == that.blockingTaskExecutor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(centralDogma, projectName, repositoryName, blockingTaskExecutor);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("centralDogma", centralDogma)
                          .add("projectName", projectName)
                          .add("repositoryName", repositoryName)
                          .add("blockingTaskExecutor", blockingTaskExecutor)
                          .toString();
    }
}
