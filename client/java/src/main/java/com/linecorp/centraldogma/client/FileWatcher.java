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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

import io.micrometer.core.instrument.MeterRegistry;

final class FileWatcher<T> extends AbstractWatcher<T> {

    private final CentralDogma centralDogma;
    private final String projectName;
    private final String repositoryName;
    private final Query<T> query;
    private final long timeoutMillis;
    private final boolean errorOnEntryNotFound;
    @Nullable
    private final Function<Object, ? extends T> mapper;
    @Nullable
    private final Executor mapperExecutor;
    private final boolean applyTemplate;
    @Nullable
    private final String variableFile;

    FileWatcher(CentralDogma centralDogma, ScheduledExecutorService watchScheduler, String projectName,
                String repositoryName, Query<T> query, long timeoutMillis, boolean errorOnEntryNotFound,
                @Nullable Function<Object, ? extends T> mapper, Executor mapperExecutor,
                long delayOnSuccessMillis, long initialDelayMillis, long maxDelayMillis, double multiplier,
                double jitterRate, @Nullable MeterRegistry meterRegistry, boolean applyTemplate,
                @Nullable String variableFile) {
        super(watchScheduler, projectName, repositoryName, query.path(), errorOnEntryNotFound,
              delayOnSuccessMillis, initialDelayMillis, maxDelayMillis, multiplier, jitterRate, meterRegistry);
        this.centralDogma = centralDogma;
        this.projectName = projectName;
        this.repositoryName = repositoryName;
        this.query = query;
        this.timeoutMillis = timeoutMillis;
        this.errorOnEntryNotFound = errorOnEntryNotFound;
        this.mapper = mapper;
        this.mapperExecutor = mapperExecutor;
        this.applyTemplate = applyTemplate;
        this.variableFile = variableFile;
    }

    @Override
    CompletableFuture<Latest<T>> doWatch(Revision lastKnownRevision, @Nullable Revision templateRevision) {
        final CompletableFuture<Entry<T>> future = centralDogma.watchFile(projectName, repositoryName,
                                                                          lastKnownRevision, query,
                                                                          timeoutMillis, errorOnEntryNotFound,
                                                                          false, applyTemplate, variableFile,
                                                                          templateRevision);
        if (mapper == null) {
            return future.thenApply(entry -> {
                if (entry == null) {
                    return null;
                }
                return new Latest<>(entry.revision(), entry.templateRevision(), entry.content());
            });
        }
        return future.thenApplyAsync(entry -> {
            if (entry == null) {
                return null;
            }
            return new Latest<>(entry.revision(), entry.templateRevision(), mapper.apply(entry.content()));
        }, mapperExecutor);
    }
}
