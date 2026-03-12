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

import static com.linecorp.centraldogma.internal.Util.unsafeCast;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;

import io.micrometer.core.instrument.MeterRegistry;

final class FilesWatcher<T> extends AbstractWatcher<T> {

    private final CentralDogma centralDogma;
    private final String projectName;
    private final String repositoryName;
    private final PathPattern pathPattern;
    private final long timeoutMillis;
    private final boolean errorOnEntryNotFound;
    @Nullable
    private final Function<Revision, ? extends T> mapper;
    @Nullable
    private final Executor mapperExecutor;

    FilesWatcher(CentralDogma centralDogma, ScheduledExecutorService watchScheduler,
                 String projectName, String repositoryName, PathPattern pathPattern,
                 long timeoutMillis, boolean errorOnEntryNotFound,
                 @Nullable Function<Object, ? extends T> mapper, Executor mapperExecutor,
                 long delayOnSuccessMillis, long initialDelayMillis, long maxDelayMillis,
                 double multiplier, double jitterRate, @Nullable MeterRegistry meterRegistry) {
        super(watchScheduler, projectName, repositoryName, pathPattern.patternString(), errorOnEntryNotFound,
              delayOnSuccessMillis, initialDelayMillis, maxDelayMillis, multiplier, jitterRate, meterRegistry);
        this.centralDogma = centralDogma;
        this.projectName = projectName;
        this.repositoryName = repositoryName;
        this.pathPattern = pathPattern;
        this.timeoutMillis = timeoutMillis;
        this.errorOnEntryNotFound = errorOnEntryNotFound;
        this.mapper = mapper != null ? unsafeCast(mapper) : null;
        this.mapperExecutor = mapperExecutor;
    }

    @Override
    CompletableFuture<Latest<T>> doWatch(Revision lastKnownRevision, @Nullable Revision templateRevision) {
        // templateRevision is not used for FilesWatcher
        final CompletableFuture<Revision> future = centralDogma.watchRepository(
                projectName, repositoryName, lastKnownRevision,
                pathPattern, timeoutMillis, errorOnEntryNotFound);
        if (mapper == null) {
            return future.thenApply(revision -> {
                if (revision == null) {
                    return null;
                }
                //noinspection unchecked
                return new Latest<>(revision, (T) revision);
            });
        }
        return future.thenApplyAsync(revision -> {
            if (revision == null) {
                return null;
            }
            return new Latest<>(revision, mapper.apply(revision));
        }, mapperExecutor);
    }
}
