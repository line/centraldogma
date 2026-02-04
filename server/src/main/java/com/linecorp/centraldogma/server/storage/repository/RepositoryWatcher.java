/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.storage.repository;

import static com.linecorp.armeria.common.util.Functions.voidFunction;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.Project;

final class RepositoryWatcher<T> {

    private static final CancellationException CANCELLATION_EXCEPTION =
            Exceptions.clearTrace(new CancellationException("parent complete"));

    private final CompletableFuture<Entry<T>> watchResult = new CompletableFuture<>();

    private final Repository repo;
    private final Revision lastKnownRev;
    private final Query<T> query;
    private final String pathPattern;
    private final boolean errorOnEntryNotFound;

    @Nullable
    private final Function<Revision, EntryTransformer<T>> transformerFactory;
    @Nullable
    private final Repository dogmaRepo;
    @Nullable
    private final Revision lastKnownTemplateRev;

    RepositoryWatcher(Repository repo, Revision lastKnownRev, Query<T> query, boolean errorOnEntryNotFound,
                      @Nullable String variableFile, @Nullable Revision lastKnownTemplateRev,
                      @Nullable Function<Revision, EntryTransformer<T>> transformerFactory) {
        this.repo = repo;
        this.lastKnownRev = lastKnownRev;
        this.query = query;
        this.transformerFactory = transformerFactory;
        if (transformerFactory == null) {
            pathPattern = query.path();
            dogmaRepo = null;
            this.lastKnownTemplateRev = null;
        } else {
            String pathPattern = query.path() + ",/**/.variables.*";
            if (variableFile != null) {
                pathPattern += ',' + variableFile;
            }
            this.pathPattern = pathPattern;
            dogmaRepo = repo.parent().repos().get(Project.REPO_DOGMA);
            this.lastKnownTemplateRev = lastKnownTemplateRev;
        }
        this.errorOnEntryNotFound = errorOnEntryNotFound;
    }

    CompletableFuture<Entry<T>> watch() {
        Revision templateRevision = lastKnownTemplateRev;
        if (templateRevision == null) {
            // For the first watch, read the latest variable revision.
            templateRevision = Revision.HEAD;
        }
        final EntryTransformer<T> transformer;
        if (transformerFactory != null) {
            transformer = transformerFactory.apply(templateRevision);
        } else {
            transformer = EntryTransformer.identity();
        }
        repo.getOrNull(lastKnownRev, query, transformer)
            .handle((oldResult, cause) -> {
                if (cause != null) {
                    watchResult.completeExceptionally(cause);
                } else {
                    final Revision oldTempRev =
                            oldResult != null ? oldResult.templateRevision() : lastKnownTemplateRev;
                    watch(lastKnownRev, oldResult, oldTempRev);
                }
                return null;
            });
        return watchResult;
    }

    private void watch(Revision lastKnownRev, @Nullable Entry<T> oldResult,
                       @Nullable Revision lastKnownTemplateRev) {
        watchRepos(lastKnownRev, lastKnownTemplateRev).thenCompose(pair -> {
            final EntryTransformer<T> transformer;
            final Revision templateRevision;
            if (transformerFactory != null) {
                templateRevision = pair.tempRev == null ? dogmaRepo.normalizeNow(Revision.HEAD)
                                                        : pair.tempRev;
                transformer = transformerFactory.apply(templateRevision);
            } else {
                templateRevision = null;
                transformer = EntryTransformer.identity();
            }

            final Revision repoRevision = pair.repoRev == null ? repo.normalizeNow(Revision.HEAD)
                                                                     : pair.repoRev;
            return repo.getOrNull(repoRevision, query, transformer).thenAccept(newResult -> {
                if (errorOnEntryNotFound && newResult == null) {
                    // The entry is removed.
                    watchResult.completeExceptionally(
                            new EntryNotFoundException(repoRevision, query.path()));
                    return;
                }

                if (newResult == null ||
                    oldResult != null && Objects.equals(oldResult.content(), newResult.content())) {
                    // Entry does not exist or did not change; watch again for more changes.
                    if (!watchResult.isDone()) {
                        // ... only when the parent future has not been cancelled.
                        watch(repoRevision, oldResult, templateRevision);
                    }
                } else {
                    watchResult.complete(newResult);
                }
            });
        }).exceptionally(voidFunction(watchResult::completeExceptionally));
    }

    private CompletableFuture<RevisionPair> watchRepos(Revision lastKnownRev,
                                                       @Nullable Revision lastKnownTempRev) {
        final CompletableFuture<RevisionPair> future = new CompletableFuture<>();

        final CompletableFuture<Revision> repoFuture = repo.watch(lastKnownRev, pathPattern,
                                                                  errorOnEntryNotFound);
        repoFuture.handle((newRev, cause) -> {
            if (cause != null) {
                future.completeExceptionally(cause);
            } else {
                future.complete(new RevisionPair(newRev, null));
            }
            return null;
        });

        final CompletableFuture<Revision> dogmaFuture;
        if (lastKnownTempRev != null) {
            dogmaFuture = dogmaRepo.watch(lastKnownTempRev, "/**/variables/**/*.json");
            dogmaFuture.handle((newTempRev, cause) -> {
                if (cause != null) {
                    future.completeExceptionally(cause);
                } else {
                    future.complete(new RevisionPair(null, newTempRev));
                }
                return null;
            });
        } else {
            dogmaFuture = null;
        }

        // One of the watched revisions has changed.
        watchResult.whenComplete((res, cause) -> {
            repoFuture.completeExceptionally(CANCELLATION_EXCEPTION);
            if (dogmaFuture != null) {
                dogmaFuture.completeExceptionally(CANCELLATION_EXCEPTION);
            }
        });
        return future;
    }

    private static final class RevisionPair {
        @Nullable
        final Revision repoRev;
        @Nullable
        final Revision tempRev;

        RevisionPair(@Nullable Revision repoRev, @Nullable Revision tempRev) {
            this.repoRev = repoRev;
            this.tempRev = tempRev;
        }
    }
}
