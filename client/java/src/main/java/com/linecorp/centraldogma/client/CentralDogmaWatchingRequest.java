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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Prepares a watch request.
 */
public abstract class CentralDogmaWatchingRequest {

    static final Executor defaultExecutor;

    static {
        Executor executor;
        try {
            final Class<?> commonPools = Class.forName("com.linecorp.armeria.common.CommonPools");
            final Method method = commonPools.getDeclaredMethod("blockingTaskExecutor");
            executor = (ScheduledExecutorService) method.invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException |
                IllegalAccessException | InvocationTargetException ignored) {
            executor = ForkJoinPool.commonPool();
        }
        defaultExecutor = executor;
    }

    private long timeoutMillis;

    CentralDogmaWatchingRequest() {
        this(WatchConstants.DEFAULT_WATCH_TIMEOUT_MILLIS);
    }

    CentralDogmaWatchingRequest(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Sets the timeout for a watch request.
     */
    public CentralDogmaWatchingRequest timeout(Duration timeout) {
        requireNonNull(timeout, "timeout");
        checkArgument(!timeout.isZero() && !timeout.isNegative(), "timeout: %s (expected: > 0)", timeout);
        return timeoutMillis(timeout.toMillis());
    }

    /**
     * Sets the timeout for a watch request in milliseconds.
     */
    public CentralDogmaWatchingRequest timeoutMillis(long timeoutMillis) {
        checkArgument(timeoutMillis > 0, "timeoutMillis: %s (expected: > 0)", timeoutMillis);
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    long timeoutMillis() {
        return timeoutMillis;
    }
}
