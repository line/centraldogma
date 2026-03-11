/*
 * Copyright 2026 LINE Corporation
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import com.linecorp.centraldogma.common.Revision;

abstract class AbstractMappingWatcher<T, U> implements Watcher<U> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractMappingWatcher.class);

    final CompletableFuture<Latest<U>> initialValueFuture = new CompletableFuture<>();
    volatile boolean closed;
    final Watcher<T> parent;

    private final boolean closeParentWhenClosing;
    private final List<Map.Entry<BiConsumer<? super Revision, ? super U>, Executor>> updateListeners =
            new CopyOnWriteArrayList<>();

    AbstractMappingWatcher(Watcher<T> parent, boolean closeParentWhenClosing) {
        this.parent = parent;
        this.closeParentWhenClosing = closeParentWhenClosing;
        parent.initialValueFuture().exceptionally(cause -> {
            initialValueFuture.completeExceptionally(cause);
            return null;
        });
    }

    @Override
    public final ScheduledExecutorService watchScheduler() {
        return parent.watchScheduler();
    }

    @Override
    public final CompletableFuture<Latest<U>> initialValueFuture() {
        return initialValueFuture;
    }

    @Override
    public final void close() {
        closed = true;
        if (!initialValueFuture.isDone()) {
            initialValueFuture.cancel(false);
        }
        if (closeParentWhenClosing) {
            parent.close();
        }
    }

    private void notifyListener(Latest<U> latest, BiConsumer<? super Revision, ? super U> listener) {
        try {
            listener.accept(latest.revision(), latest.value());
        } catch (Exception e) {
            logger.warn("Unexpected exception is raised from {}: rev={}",
                    listener, latest.revision(), e);
        }
    }

    protected final void notifyListeners(Latest<U> latest) {
        if (closed) {
            return;
        }

        for (Map.Entry<BiConsumer<? super Revision, ? super U>, Executor> entry : updateListeners) {
            final BiConsumer<? super Revision, ? super U> listener = entry.getKey();
            final Executor executor = entry.getValue();
            executor.execute(() -> notifyListener(latest, listener));
        }
    }

    protected abstract @Nullable Latest<U> mappedLatest();

    @Override
    public final Latest<U> latest() {
        final Latest<U> mappedLatest = mappedLatest();
        if (mappedLatest == null) {
            throw new IllegalStateException("value not available yet");
        }
        return mappedLatest;
    }

    @Override
    public final void watch(BiConsumer<? super Revision, ? super U> listener, Executor executor) {
        requireNonNull(listener, "listener");
        requireNonNull(executor, "executor");
        updateListeners.add(Maps.immutableEntry(listener, executor));

        final Latest<U> mappedLatest = mappedLatest();
        if (mappedLatest != null) {
            // There's a chance that listener.accept(...) is called twice for the same value
            // if this watch method is called:
            // - after "mappedLatest = newLatest;" is invoked.
            // - and before notifyListener() is called.
            // However, it's such a rare case and we usually call `watch` method after creating a Watcher,
            // which means mappedLatest is probably not set yet, so we don't use a lock to guarantee
            // the atomicity.
            executor.execute(() -> listener.accept(mappedLatest.revision(), mappedLatest.value()));
        }
    }

    @Override
    public final void watch(BiConsumer<? super Revision, ? super U> listener) {
        watch(listener, parent.watchScheduler());
    }
}
