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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

public interface Watcher<T> extends SafeCloseable {

    /**
     * Returns the {@link CompletableFuture} which is completed when the initial value retrieval is done
     * successfully.
     */
    CompletableFuture<Latest<T>> initialValueFuture();

    /**
     * Waits for the initial value to be available.
     *
     * @throws CancellationException if this watcher has been closed by {@link #close()}
     */
    default Latest<T> awaitInitialValue() throws InterruptedException {
        try {
            return initialValueFuture().get();
        } catch (ExecutionException e) {
            // Should never occur because we never complete this future exceptionally.
            throw new Error(e);
        }
    }

    /**
     * Waits for the initial value to be available.
     *
     * @throws CancellationException if this watcher has been closed by {@link #close()}
     */
    default Latest<T> awaitInitialValue(long timeout, TimeUnit unit) throws InterruptedException,
                                                                            TimeoutException {
        requireNonNull(unit, "unit");
        try {
            return initialValueFuture().get(timeout, unit);
        } catch (ExecutionException e) {
            // Should never occur because we never complete this future exceptionally.
            throw new Error(e);
        }
    }

    /**
     * Returns the latest {@link Revision} and value of {@code watchFile()} result.
     *
     * @throws IllegalStateException if the value is not available yet.
     *                               Use {@link #awaitInitialValue(long, TimeUnit)} first or
     *                               add a listener using {@link #watch(BiConsumer)} instead.
     */
    Latest<T> latest();

    /**
     * Returns the latest value of {@code watchFile()} result.
     *
     * @throws IllegalStateException if the value is not available yet.
     *                               Use {@link #awaitInitialValue(long, TimeUnit)} first or
     *                               add a listener using {@link #watch(BiConsumer)} instead.
     */
    default T latestValue() {
        return latest().value();
    }

    /**
     * Returns the latest value of {@code watchFile()} result.
     *
     * @param defaultValue the default value which is returned when the value is not available yet
     */
    @Nullable
    default T latestValue(@Nullable T defaultValue) {
        final CompletableFuture<Latest<T>> initialValueFuture = initialValueFuture();
        if (initialValueFuture.isDone() && !initialValueFuture.isCompletedExceptionally()) {
            return latest().value();
        } else {
            return defaultValue;
        }
    }

    /**
     * Stops watching the file specified in the {@link Query}.
     */
    @Override
    void close();

    /**
     * Registers a {@link BiConsumer} that will be invoked when the value of the watched entry becomes
     * available or changes.
     */
    void watch(BiConsumer<? super Revision, ? super T> listener);

    /**
     * Registers a {@link Consumer} that will be invoked when the value of the watched entry becomes available
     * or changes.
     */
    default void watch(Consumer<? super T> listener) {
        requireNonNull(listener, "listener");
        watch((revision, value) -> listener.accept(value));
    }
}
