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

import com.linecorp.centraldogma.common.EntryNotFoundException;

/**
 * Prepares a watch request.
 */
public abstract class WatchOptions {

    private long timeoutMillis;
    private boolean errorOnEntryNotFound;

    WatchOptions() {
        this(WatchConstants.DEFAULT_WATCH_TIMEOUT_MILLIS,
             WatchConstants.DEFAULT_WATCH_ERROR_ON_ENTRY_NOT_FOUND);
    }

    WatchOptions(long timeoutMillis, boolean errorOnEntryNotFound) {
        this.timeoutMillis = timeoutMillis;
        this.errorOnEntryNotFound = errorOnEntryNotFound;
    }

    /**
     * Sets the timeout for a watch request.
     */
    public WatchOptions timeout(Duration timeout) {
        requireNonNull(timeout, "timeout");
        checkArgument(!timeout.isZero() && !timeout.isNegative(), "timeout: %s (expected: > 0)", timeout);
        return timeoutMillis(timeout.toMillis());
    }

    /**
     * Sets the timeout for a watch request in milliseconds.
     */
    public WatchOptions timeoutMillis(long timeoutMillis) {
        checkArgument(timeoutMillis > 0, "timeoutMillis: %s (expected: > 0)", timeoutMillis);
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    /**
     * Sets whether to throw an {@link EntryNotFoundException} if the watch target does not exist.
     */
    public WatchOptions errorOnEntryNotFound(boolean errorOnEntryNotFound) {
        this.errorOnEntryNotFound = errorOnEntryNotFound;
        return this;
    }

    long timeoutMillis() {
        return timeoutMillis;
    }

    boolean errorOnEntryNotFound() {
        return errorOnEntryNotFound;
    }
}
