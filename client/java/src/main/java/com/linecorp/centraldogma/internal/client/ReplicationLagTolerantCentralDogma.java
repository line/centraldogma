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
package com.linecorp.centraldogma.internal.client;

import static com.google.common.base.Preconditions.checkArgument;
import static com.spotify.futures.CompletableFutures.exceptionallyCompletedFuture;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;

import com.linecorp.centraldogma.client.AbstractCentralDogma;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.RepositoryInfo;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.common.RevisionRange;

/**
 * A {@link CentralDogma} client that retries the request automatically when a {@link RevisionNotFoundException}
 * was raised but it is certain that a given {@link Revision} exists.
 */
public final class ReplicationLagTolerantCentralDogma extends AbstractCentralDogma {

    private final CentralDogma delegate;
    private final int maxRetries;
    private final long retryIntervalMillis;
    private final Map<RepoId, Revision> latestKnownRevisions = new LinkedHashMap<RepoId, Revision>() {
        private static final long serialVersionUID = 3587793379404809027L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<RepoId, Revision> eldest) {
            // Keep only up to 8192 repositories, which should be enough for almost all cases.
            return size() > 8192;
        }
    };

    public ReplicationLagTolerantCentralDogma(ScheduledExecutorService executor, CentralDogma delegate,
                                              int maxRetries, long retryIntervalMillis) {
        super(executor);

        requireNonNull(delegate, "delegate");
        checkArgument(maxRetries > 0, "maxRetries: %s (expected: > 0)", maxRetries);
        checkArgument(retryIntervalMillis >= 0,
                      "retryIntervalMillis: %s (expected: >= 0)", retryIntervalMillis);

        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.retryIntervalMillis = retryIntervalMillis;
    }

    @Override
    public CompletableFuture<Void> createProject(String projectName) {
        return delegate.createProject(projectName);
    }

    @Override
    public CompletableFuture<Void> removeProject(String projectName) {
        return delegate.removeProject(projectName);
    }

    @Override
    public CompletableFuture<Void> purgeProject(String projectName) {
        return delegate.purgeProject(projectName);
    }

    @Override
    public CompletableFuture<Void> unremoveProject(String projectName) {
        return delegate.unremoveProject(projectName);
    }

    @Override
    public CompletableFuture<Set<String>> listProjects() {
        return delegate.listProjects();
    }

    @Override
    public CompletableFuture<Set<String>> listRemovedProjects() {
        return delegate.listRemovedProjects();
    }

    @Override
    public CompletableFuture<Void> createRepository(String projectName, String repositoryName) {
        return delegate.createRepository(projectName, repositoryName);
    }

    @Override
    public CompletableFuture<Void> removeRepository(String projectName, String repositoryName) {
        return delegate.removeRepository(projectName, repositoryName);
    }

    @Override
    public CompletableFuture<Void> purgeRepository(String projectName, String repositoryName) {
        return delegate.purgeRepository(projectName, repositoryName);
    }

    @Override
    public CompletableFuture<Void> unremoveRepository(String projectName, String repositoryName) {
        return delegate.unremoveRepository(projectName, repositoryName);
    }

    @Override
    public CompletableFuture<Map<String, RepositoryInfo>> listRepositories(String projectName) {
        return executeWithRetries(
                () -> delegate.listRepositories(projectName),
                (res, cause) -> {
                    if (res != null) {
                        for (RepositoryInfo info : res.values()) {
                            if (!updateLatestKnownRevision(projectName, info.name(), info.headRevision())) {
                                return true;
                            }
                        }
                    }
                    return false;
                });
    }

    @Override
    public CompletableFuture<Set<String>> listRemovedRepositories(String projectName) {
        return delegate.listRemovedRepositories(projectName);
    }

    @Override
    public CompletableFuture<Revision> normalizeRevision(
            String projectName, String repositoryName, Revision revision) {
        return executeWithRetries(
                () -> delegate.normalizeRevision(projectName, repositoryName, revision),
                (res, cause) -> {
                    if (cause != null) {
                        return handleRevisionNotFound(projectName, repositoryName, revision, cause);
                    }

                    if (revision.isRelative()) {
                        final Revision headRevision = res.forward(-(revision.major() + 1));
                        return !updateLatestKnownRevision(projectName, repositoryName, headRevision);
                    }

                    updateLatestKnownRevision(projectName, repositoryName, revision);
                    return false;
                });
    }

    @Override
    public CompletableFuture<Map<String, EntryType>> listFiles(
            String projectName, String repositoryName, Revision revision, String pathPattern) {
        return normalizeRevisionAndExecuteWithRetries(
                projectName, repositoryName, revision,
                normRev -> delegate.listFiles(projectName, repositoryName, normRev, pathPattern));
    }

    @Override
    public <T> CompletableFuture<Entry<T>> getFile(
            String projectName, String repositoryName, Revision revision, Query<T> query) {
        return normalizeRevisionAndExecuteWithRetries(
                projectName, repositoryName, revision,
                normRev -> delegate.getFile(projectName, repositoryName, normRev, query));
    }

    @Override
    public CompletableFuture<Map<String, Entry<?>>> getFiles(
            String projectName, String repositoryName, Revision revision, String pathPattern) {
        return normalizeRevisionAndExecuteWithRetries(
                projectName, repositoryName, revision,
                normRev -> delegate.getFiles(projectName, repositoryName, normRev, pathPattern));
    }

    @Override
    public <T> CompletableFuture<MergedEntry<T>> mergeFiles(
            String projectName, String repositoryName, Revision revision,
            MergeQuery<T> mergeQuery) {
        return normalizeRevisionAndExecuteWithRetries(
                projectName, repositoryName, revision,
                normRev -> delegate.mergeFiles(projectName, repositoryName, normRev, mergeQuery));
    }

    @Override
    public CompletableFuture<List<Commit>> getHistory(
            String projectName, String repositoryName, Revision from,
            Revision to, String pathPattern) {
        return normalizeRevisionsAndExecuteWithRetries(
                projectName, repositoryName, from, to,
                (normFromRev, normToRev) -> delegate.getHistory(projectName, repositoryName,
                                                                normFromRev, normToRev, pathPattern));
    }

    @Override
    public <T> CompletableFuture<Change<T>> getDiff(
            String projectName, String repositoryName, Revision from, Revision to, Query<T> query) {
        return normalizeRevisionsAndExecuteWithRetries(
                projectName, repositoryName, from, to,
                (normFromRev, normToRev) -> delegate.getDiff(projectName, repositoryName,
                                                             normFromRev, normToRev, query));
    }

    @Override
    public CompletableFuture<List<Change<?>>> getDiffs(
            String projectName, String repositoryName, Revision from, Revision to, String pathPattern) {
        return normalizeRevisionsAndExecuteWithRetries(
                projectName, repositoryName, from, to,
                (normFromRev, normToRev) -> delegate.getDiffs(projectName, repositoryName,
                                                              normFromRev, normToRev, pathPattern));
    }

    @Override
    public CompletableFuture<List<Change<?>>> getPreviewDiffs(
            String projectName, String repositoryName, Revision baseRevision,
            Iterable<? extends Change<?>> changes) {
        return normalizeRevisionAndExecuteWithRetries(
                projectName, repositoryName, baseRevision,
                normBaseRev -> delegate.getPreviewDiffs(projectName, repositoryName, normBaseRev, changes));
    }

    @Override
    public CompletableFuture<PushResult> push(
            String projectName, String repositoryName, Revision baseRevision,
            String summary, String detail, Markup markup, Iterable<? extends Change<?>> changes) {
        return executeWithRetries(
                () -> delegate.push(projectName, repositoryName, baseRevision,
                                    summary, detail, markup, changes),
                pushRetryPredicate(projectName, repositoryName, baseRevision));
    }

    @Override
    public CompletableFuture<PushResult> push(
            String projectName, String repositoryName, Revision baseRevision,
            Author author, String summary, String detail, Markup markup,
            Iterable<? extends Change<?>> changes) {
        return executeWithRetries(
                () -> delegate.push(projectName, repositoryName, baseRevision,
                                    author, summary, detail, markup, changes),
                pushRetryPredicate(projectName, repositoryName, baseRevision));
    }

    private BiPredicate<PushResult, Throwable> pushRetryPredicate(
            String projectName, String repositoryName, Revision baseRevision) {

        return (res, cause) -> {
            if (cause != null) {
                return handleRevisionNotFound(projectName, repositoryName, baseRevision, cause);
            }

            updateLatestKnownRevision(projectName, repositoryName, res.revision());
            return false;
        };
    }

    @Override
    public CompletableFuture<Revision> watchRepository(
            String projectName, String repositoryName, Revision lastKnownRevision,
            String pathPattern, long timeoutMillis) {

        return normalizeRevisionAndExecuteWithRetries(
                projectName, repositoryName, lastKnownRevision,
                normLastKnownRevision -> {
                    return delegate.watchRepository(projectName, repositoryName, normLastKnownRevision,
                                                    pathPattern, timeoutMillis)
                                   .thenApply(newLastKnownRevision -> {
                                       if (newLastKnownRevision != null) {
                                           updateLatestKnownRevision(projectName, repositoryName,
                                                                     newLastKnownRevision);
                                       }
                                       return newLastKnownRevision;
                                   });
                });
    }

    @Override
    public <T> CompletableFuture<Entry<T>> watchFile(
            String projectName, String repositoryName, Revision lastKnownRevision,
            Query<T> query, long timeoutMillis) {

        return normalizeRevisionAndExecuteWithRetries(
                projectName, repositoryName, lastKnownRevision,
                normLastKnownRevision -> {
                    return delegate.watchFile(projectName, repositoryName, normLastKnownRevision,
                                              query, timeoutMillis)
                                   .thenApply(entry -> {
                                       if (entry != null) {
                                           updateLatestKnownRevision(projectName, repositoryName,
                                                                     entry.revision());
                                       }
                                       return entry;
                                   });
                });
    }

    /**
     * Normalizes the given {@link Revision} and then executes the task by calling {@code taskRunner.apply()}
     * with the normalized {@link Revision}. The task can be executed repetitively when the task failed with
     * a {@link RevisionNotFoundException} for the {@link Revision} which is known to exist.
     */
    private <T> CompletableFuture<T> normalizeRevisionAndExecuteWithRetries(
            String projectName, String repositoryName, Revision revision,
            Function<Revision, CompletableFuture<T>> taskRunner) {
        return normalizeRevision(projectName, repositoryName, revision)
                .thenCompose(normRev -> executeWithRetries(
                        () -> taskRunner.apply(normRev),
                        (res, cause) -> cause != null &&
                                        handleRevisionNotFound(projectName, repositoryName, normRev, cause)));
    }

    /**
     * Normalizes the given {@link Revision} range and then executes the task by calling
     * {@code taskRunner.apply()} with the normalized {@link Revision} range. The task can be executed
     * repetitively when the task failed with a {@link RevisionNotFoundException} for the {@link Revision}
     * range which is known to exist.
     */
    private <T> CompletableFuture<T> normalizeRevisionsAndExecuteWithRetries(
            String projectName, String repositoryName, Revision from, Revision to,
            BiFunction<Revision, Revision, CompletableFuture<T>> taskRunner) {

        if (to == null) {
            return exceptionallyCompletedFuture(new NullPointerException("to"));
        }

        if (from == null) {
            return exceptionallyCompletedFuture(new NullPointerException("from"));
        }

        final int distance = to.major() - from.major();
        final Revision baseRevision = to.compareTo(from) >= 0 ? to : from;

        return normalizeRevision(projectName, repositoryName, baseRevision).thenCompose(normBaseRev -> {
            final Revision normFromRev;
            final Revision normToRev;
            if (distance >= 0) {
                normToRev = normBaseRev;
                normFromRev = normBaseRev.backward(distance);
            } else {
                normFromRev = normBaseRev;
                normToRev = normBaseRev.backward(-distance);
            }

            return executeWithRetries(
                    () -> taskRunner.apply(normFromRev, normToRev),
                    (res, cause) -> {
                        if (cause == null) {
                            return false;
                        }
                        return handleRevisionNotFound(projectName, repositoryName, baseRevision, cause);
                    });
        });
    }

    /**
     * Executes the task by calling {@code taskRunner.get()} and re-executes the task if {@code retryPredicated}
     * returns {@code true}. This method is used as a building block for sending a request repetitively
     * when the request has failed due to replication lag.
     */
    private <T> CompletableFuture<T> executeWithRetries(
            Supplier<CompletableFuture<T>> taskRunner,
            BiPredicate<T, Throwable> retryPredicate) {
        return executeWithRetries(taskRunner, retryPredicate, 0);
    }

    private <T> CompletableFuture<T> executeWithRetries(
            Supplier<CompletableFuture<T>> taskRunner,
            BiPredicate<T, Throwable> retryPredicate,
            int attemptsSoFar) {

        return CompletableFutures.handleCompose(taskRunner.get(), (res, cause) -> {
            final int nextAttemptsSoFar = attemptsSoFar + 1;
            if (!retryPredicate.test(res, cause) || nextAttemptsSoFar > maxRetries) {
                if (cause == null) {
                    return completedFuture(res);
                } else {
                    return exceptionallyCompletedFuture(cause);
                }
            }

            final CompletableFuture<T> nextAttemptFuture = new CompletableFuture<>();
            executor().schedule(() -> {
                try {
                    executeWithRetries(taskRunner, retryPredicate,
                                       nextAttemptsSoFar).handle((newRes, newCause) -> {
                        if (newCause != null) {
                            nextAttemptFuture.completeExceptionally(newCause);
                        } else {
                            nextAttemptFuture.complete(newRes);
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    nextAttemptFuture.completeExceptionally(t);
                }
            }, retryIntervalMillis, TimeUnit.MILLISECONDS);

            return nextAttemptFuture;
        }).toCompletableFuture();
    }

    /**
     * Returns {@code true} to indicate that the request must be retried if {@code cause} is
     * a {@link RevisionNotFoundException} and the specified {@link Revision} is supposed to exist.
     */
    private boolean handleRevisionNotFound(
            String projectName, String repositoryName, Revision revision, Throwable cause) {

        requireNonNull(cause, "cause");

        if (!(cause instanceof RevisionNotFoundException)) {
            return false;
        }

        final Revision latestKnownRevision = latestKnownRevision(projectName, repositoryName);
        if (latestKnownRevision == null) {
            return false;
        }

        if (revision.isRelative()) {
            return revision.major() + latestKnownRevision.major() >= 0;
        } else {
            return revision.major() <= latestKnownRevision.major();
        }
    }

    @Nullable
    @VisibleForTesting
    Revision latestKnownRevision(String projectName, String repositoryName) {
        synchronized (latestKnownRevisions) {
            return latestKnownRevisions.get(new RepoId(projectName, repositoryName));
        }
    }

    /**
     * Updates the latest known revision for the specified repository.
     *
     * @return {@code true} if the latest known revision has been updated to the specified {@link Revision} or
     *         the latest known revision is equal to the specified {@link Revision}. {@code false} otherwise.
     */
    private boolean updateLatestKnownRevision(String projectName, String repositoryName, Revision newRevision) {
        final RepoId id = new RepoId(projectName, repositoryName);
        synchronized (latestKnownRevisions) {
            final Revision oldRevision = latestKnownRevisions.get(id);
            if (oldRevision == null) {
                latestKnownRevisions.put(id, newRevision);
                return true;
            }

            final int comparison = oldRevision.compareTo(newRevision);
            if (comparison < 0) {
                latestKnownRevisions.put(id, newRevision);
                return true;
            }

            return comparison == 0;
        }
    }

    private static final class RepoId {
        private final String projectName;
        private final String repositoryName;

        RepoId(String projectName, String repositoryName) {
            this.projectName = projectName;
            this.repositoryName = repositoryName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RepoId)) {
                return false;
            }
            final RepoId that = (RepoId) o;
            return projectName.equals(that.projectName) && repositoryName.equals(that.repositoryName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectName, repositoryName);
        }

        @Override
        public String toString() {
            return projectName + '/' + repositoryName;
        }
    }
}
