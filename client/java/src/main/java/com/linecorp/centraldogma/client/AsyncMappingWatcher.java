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

import com.google.common.collect.Maps;
import com.linecorp.centraldogma.common.Revision;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

final class AsyncMappingWatcher<T, U> implements Watcher<U> {

    private static final Logger logger = LoggerFactory.getLogger(AsyncMappingWatcher.class);

    static <T, U> AsyncMappingWatcher<T, U> of(Watcher<T> parent, Function<? super T, ? extends CompletableFuture<? extends U>> mapper,
                                               boolean closeParentWhenClosing) {
        requireNonNull(parent, "parent");
        requireNonNull(mapper, "mapper");
        return new AsyncMappingWatcher<>(parent, mapper, closeParentWhenClosing);
    }

    private final Watcher<T> parent;
    private final Function<? super T, ? extends CompletableFuture<? extends U>> mapper;
    private final boolean closeParentWhenClosing;
    private final CompletableFuture<Latest<U>> initialValueFuture = new CompletableFuture<>();
    private final List<Entry<BiConsumer<? super Revision, ? super U>, Executor>> updateListeners =
            new CopyOnWriteArrayList<>();

    private final AtomicReference<@Nullable Latest<U>> mappedLatest = new AtomicReference<>();
    private volatile boolean closed;

    private static <U> boolean isUpdate(Latest<U> newLatest, @Nullable Latest<U> existing) {
        if (existing == null) return true;
        if (Objects.equals(existing.value(), newLatest.value())) return false;
        return newLatest.revision().compareTo(existing.revision()) >= 0;
    }

    AsyncMappingWatcher(Watcher<T> parent, Function<? super T, ? extends CompletableFuture<? extends U>> mapper,
                        boolean closeParentWhenClosing) {
        this.parent = parent;
        this.mapper = mapper;
        this.closeParentWhenClosing = closeParentWhenClosing;
        parent.initialValueFuture().exceptionally(cause -> {
            initialValueFuture.completeExceptionally(cause);
            return null;
        });
        final Consumer<Throwable> reportFailure = (e) -> {
            logger.warn("Unexpected exception is raised from mapper.apply(). mapper: {}", mapper, e);
            if (!initialValueFuture.isDone()) {
                initialValueFuture.completeExceptionally(e);
            }
            close();
        };
        parent.watch((revision, value) -> {
            if (closed) {
                return;
            }
            final CompletableFuture<? extends U> mappedValueFuture;
            try {
                mappedValueFuture = mapper.apply(value);
            } catch (Exception e) {
                reportFailure.accept(e);
                return;
            }
            mappedValueFuture.whenComplete((mappedValue, e) -> {
                if (null != e) {
                    reportFailure.accept(e);
                    return;
                }

                // mappedValue can be nullable which is fine.
                final Latest<U> newLatest = new Latest<>(revision, mappedValue);
                final Latest<U> oldLatest = mappedLatest.getAndUpdate(
                        existing -> isUpdate(newLatest, existing) ? newLatest : existing
                );
                if (!isUpdate(newLatest, oldLatest)) {
                    return;
                }
                notifyListeners(newLatest);
                if (!initialValueFuture.isDone()) {
                    initialValueFuture.complete(newLatest);
                }
            });

        });
    }

    private void notifyListeners(Latest<U> latest) {
        if (closed) {
            return;
        }

        for (Entry<BiConsumer<? super Revision, ? super U>, Executor> entry : updateListeners) {
            final BiConsumer<? super Revision, ? super U> listener = entry.getKey();
            final Executor executor = entry.getValue();
            executor.execute(() -> notifyListener(latest, listener));
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

    @Override
    public ScheduledExecutorService watchScheduler() {
        return parent.watchScheduler();
    }

    @Override
    public CompletableFuture<Latest<U>> initialValueFuture() {
        return initialValueFuture;
    }

    @Override
    public Latest<U> latest() {
        final Latest<U> mappedLatest = this.mappedLatest.get();
        if (mappedLatest == null) {
            throw new IllegalStateException("value not available yet");
        }
        return mappedLatest;
    }

    @Override
    public void close() {
        closed = true;
        if (!initialValueFuture.isDone()) {
            initialValueFuture.cancel(false);
        }
        if (closeParentWhenClosing) {
            parent.close();
        }
    }

    @Override
    public void watch(BiConsumer<? super Revision, ? super U> listener) {
        watch(listener, parent.watchScheduler());
    }

    @Override
    public void watch(BiConsumer<? super Revision, ? super U> listener, Executor executor) {
        requireNonNull(listener, "listener");
        requireNonNull(executor, "executor");
        updateListeners.add(Maps.immutableEntry(listener, executor));

        final Latest<U> mappedLatest = this.mappedLatest.get();
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
    public String toString() {
        return toStringHelper(this)
                .add("parent", parent)
                .add("mapper", mapper)
                .add("closed", closed)
                .toString();
    }
}
