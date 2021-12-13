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

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Revision;

final class MappingWatcher<T, U> implements Watcher<U> {

    static <T, U> MappingWatcher<T, U> of(Watcher<T> parent, Function<? super T, ? extends U> mapper,
                                          Executor executor, boolean closeParentWhenClosing) {
        requireNonNull(parent, "parent");
        requireNonNull(mapper, "mapper");
        requireNonNull(executor, "executor");
        // TODO(minwoo): extract mapper function and combine it with the new mapper.
        return new MappingWatcher<>(parent, mapper, executor, closeParentWhenClosing);
    }

    private final Watcher<T> parent;
    private final Function<? super T, ? extends U> mapper;
    private final Executor mapperExecutor;
    private final boolean closeParentWhenClosing;
    private final CompletableFuture<Latest<U>> initialValueFuture = new CompletableFuture<>();

    @Nullable
    private volatile Latest<U> mappedLatest;
    private volatile boolean closed;

    MappingWatcher(Watcher<T> parent, Function<? super T, ? extends U> mapper, Executor mapperExecutor,
                   boolean closeParentWhenClosing) {
        this.parent = parent;
        this.mapper = mapper;
        this.mapperExecutor = mapperExecutor;
        this.closeParentWhenClosing = closeParentWhenClosing;
        parent.initialValueFuture().thenAcceptAsync(latest -> {
            if (latest != null) {
                final Latest<U> mappedLatest = new Latest<>(latest.revision(), mapper.apply(latest.value()));
                this.mappedLatest = mappedLatest;
                initialValueFuture.complete(mappedLatest);
            }
        }, mapperExecutor);
    }

    @Override
    public void start() {
        parent.start();
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
        final Latest<U> mappedLatest = this.mappedLatest;
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
        watch(listener, mapperExecutor);
    }

    @Override
    public void watch(BiConsumer<? super Revision, ? super U> listener, Executor executor) {
        requireNonNull(listener, "listener");
        requireNonNull(executor, "executor");
        parent.watch(map(listener), executor);
    }

    private BiConsumer<Revision, T> map(BiConsumer<? super Revision, ? super U> listener) {
        return (revision, value) -> {
            if (closed) {
                return;
            }
            final U newValue = mapper.apply(value);
            final Latest<U> oldLatest = mappedLatest;
            boolean notifyListener = true;
            if (oldLatest != null) {
                notifyListener = oldLatest.value().equals(newValue);
            }
            if (notifyListener) {
                final Latest<U> newLatest = new Latest<>(revision, newValue);
                mappedLatest = newLatest;
                if (!initialValueFuture.isDone()) {
                    initialValueFuture.complete(newLatest);
                }
                listener.accept(revision, newValue);
            }
        };
    }

    @Override
    public String toString() {
        return toStringHelper(this).omitNullValues()
                                   .add("parent", parent)
                                   .add("mapper", mapper)
                                   .add("mapperExecutor", mapperExecutor)
                                   .add("closed", closed)
                                   .toString();
    }
}
