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

package com.linecorp.centraldogma.client;

import static com.linecorp.centraldogma.internal.PathPatternUtil.toPathPattern;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.spotify.futures.CompletableFutures;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A skeletal {@link CentralDogma} implementation.
 */
public abstract class AbstractCentralDogma implements CentralDogma {

    private final ScheduledExecutorService blockingTaskExecutor;
    @Nullable
    private final MeterRegistry meterRegistry;

    /**
     * Creates a new instance.
     *
     * @param blockingTaskExecutor the {@link ScheduledExecutorService} which will be used for scheduling the
     *                             tasks related with automatic retries and invoking the callbacks for
     *                             watched changes.
     */
    protected AbstractCentralDogma(ScheduledExecutorService blockingTaskExecutor,
                                   @Nullable MeterRegistry meterRegistry) {
        this.blockingTaskExecutor = requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        this.meterRegistry = meterRegistry;
    }

    private static Change<?> toChange(String repoPath, Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String name = file.getFileName().toString().toLowerCase();

            if (name.endsWith(".json")) {
                return Change.ofJsonUpsert(repoPath, Jackson.readTree(bytes));
            }
            if (name.endsWith(".yml") || name.endsWith(".yaml") ||
                (Files.probeContentType(file) != null &&
                 Files.probeContentType(file).startsWith("text/"))) {
                return Change.ofTextUpsert(repoPath, new String(bytes, UTF_8));
            }
            return Change.ofTextUpsert(repoPath, new String(bytes, UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Change for " + file, e);
        }
    }

    /**
     * Returns the {@link ScheduledExecutorService} which is used for scheduling the tasks related with
     * automatic retries and invoking the callbacks for watched changes.
     */
    protected final ScheduledExecutorService executor() {
        return blockingTaskExecutor;
    }

    @Override
    public CentralDogmaRepository forRepo(String projectName, String repositoryName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repositoryName, "repositoryName");
        return new CentralDogmaRepository(this, projectName, repositoryName, blockingTaskExecutor,
                                          meterRegistry);
    }

    @Override
    public final CompletableFuture<Entry<?>> getFile(
            String projectName, String repositoryName, Revision revision, String path) {
        return CentralDogma.super.getFile(projectName, repositoryName, revision, path);
    }

    @Override
    public final CompletableFuture<MergedEntry<?>> mergeFiles(
            String projectName, String repositoryName, Revision revision, MergeSource... mergeSources) {
        return CentralDogma.super.mergeFiles(projectName, repositoryName, revision, mergeSources);
    }

    @Override
    public final CompletableFuture<MergedEntry<?>> mergeFiles(
            String projectName, String repositoryName, Revision revision, Iterable<MergeSource> mergeSources) {
        return CentralDogma.super.mergeFiles(projectName, repositoryName, revision, mergeSources);
    }

    @Override
    public final CompletableFuture<List<Commit>> getHistory(
            String projectName, String repositoryName, Revision from, Revision to) {
        return CentralDogma.super.getHistory(projectName, repositoryName, from, to);
    }

    @Override
    public final CompletableFuture<Change<?>> getDiff(
            String projectName, String repositoryName, Revision from, Revision to, String path) {
        return CentralDogma.super.getDiff(projectName, repositoryName, from, to, path);
    }

    @Override
    public final CompletableFuture<List<Change<?>>> getPreviewDiffs(
            String projectName, String repositoryName, Revision baseRevision, Change<?>... changes) {
        return CentralDogma.super.getPreviewDiffs(projectName, repositoryName, baseRevision, changes);
    }

    @Override
    public final CompletableFuture<PushResult> push(
            String projectName, String repositoryName, Revision baseRevision,
            String summary, Change<?>... changes) {
        return CentralDogma.super.push(projectName, repositoryName, baseRevision, summary, changes);
    }

    @Override
    public final CompletableFuture<PushResult> push(
            String projectName, String repositoryName, Revision baseRevision,
            String summary, Iterable<? extends Change<?>> changes) {
        return CentralDogma.super.push(projectName, repositoryName, baseRevision, summary, changes);
    }

    @Override
    public final CompletableFuture<PushResult> push(
            String projectName, String repositoryName, Revision baseRevision,
            String summary, String detail, Markup markup, Change<?>... changes) {
        return CentralDogma.super.push(projectName, repositoryName, baseRevision,
                                       summary, detail, markup, changes);
    }

    @Override
    public final CompletableFuture<PushResult> push(
            String projectName, String repositoryName, Revision baseRevision,
            Author author, String summary, Change<?>... changes) {
        return CentralDogma.super.push(projectName, repositoryName, baseRevision, author, summary, changes);
    }

    @Override
    public final CompletableFuture<PushResult> push(
            String projectName, String repositoryName, Revision baseRevision,
            Author author, String summary, Iterable<? extends Change<?>> changes) {
        return CentralDogma.super.push(projectName, repositoryName, baseRevision, author, summary, changes);
    }

    @Override
    public final CompletableFuture<PushResult> push(
            String projectName, String repositoryName, Revision baseRevision,
            Author author, String summary, String detail, Markup markup, Change<?>... changes) {
        return CentralDogma.super.push(projectName, repositoryName, baseRevision,
                                       author, summary, detail, markup, changes);
    }

    @Override
    public <T, U> Watcher<U> fileWatcher(
            String projectName, String repositoryName, Query<T> query,
            Function<? super T, ? extends U> function) {
        return fileWatcher(projectName, repositoryName, query, function, blockingTaskExecutor);
    }

    @Override
    public <T, U> Watcher<U> fileWatcher(String projectName, String repositoryName, Query<T> query,
                                         Function<? super T, ? extends U> function, Executor executor) {
        //noinspection unchecked
        return (Watcher<U>) forRepo(projectName, repositoryName).watcher(query)
                                                                .map(function)
                                                                .mapperExecutor(executor)
                                                                .start();
    }

    @Override
    public <T> Watcher<T> repositoryWatcher(
            String projectName, String repositoryName, String pathPattern,
            Function<Revision, ? extends T> function) {
        return repositoryWatcher(projectName, repositoryName, pathPattern, function, blockingTaskExecutor);
    }

    @Override
    public <T> Watcher<T> repositoryWatcher(String projectName, String repositoryName, String pathPattern,
                                            Function<Revision, ? extends T> function, Executor executor) {
        //noinspection unchecked
        return (Watcher<T>) forRepo(projectName, repositoryName).watcher(toPathPattern(pathPattern))
                                                                .map(function)
                                                                .mapperExecutor(executor)
                                                                .start();
    }

    @Override
    public CompletableFuture<ImportResult> importDir(Path dir) {
        requireNonNull(dir, "dir");
        if (dir.getNameCount() < 2) {
            return CompletableFutures.exceptionallyCompletedFuture(new IllegalArgumentException(
                    "Path must be <project>/<repo>[/…]: " + dir));
        }
        final String project = dir.getName(0).toString();
        final String repo = dir.getName(1).toString();
        final Path norm = dir.toAbsolutePath().normalize();
        final CentralDogmaRepository centralDogmaRepository = forRepo(project, repo);
        final CentralDogma centralDogma = centralDogmaRepository.centralDogma();

        return centralDogma.createProject(project)
                           .thenCompose(unused -> centralDogma.createRepository(project, repo))
                           .thenCompose(a -> centralDogmaRepository.importDir(norm));
    }

    @Override
    public CompletableFuture<ImportResult> importResourceDir(String dir) {
        return null;
    }

    @Override
    public CompletableFuture<ImportResult> importResourceDir(String dir, ClassLoader classLoader) {
        return null;
    }

    /**
     * Normalizes the specified {@link Revision} only if it is a relative revision.
     *
     * @return the absolute {@link Revision}
     */
    protected final CompletableFuture<Revision> maybeNormalizeRevision(
            String projectName, String repositoryName, Revision revision) {

        if (revision.isRelative()) {
            return normalizeRevision(projectName, repositoryName, revision);
        } else {
            return CompletableFuture.completedFuture(revision);
        }
    }
}
