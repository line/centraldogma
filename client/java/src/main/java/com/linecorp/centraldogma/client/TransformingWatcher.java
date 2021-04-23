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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Revision;

final class TransformingWatcher<T, U> implements Watcher<U> {

    private final Watcher<T> parent;
    private final Function<T, U> transformer;

    @Nullable
    private volatile Latest<U> transformedLatest;
    private volatile boolean closed;

    TransformingWatcher(Watcher<T> parent, Function<T, U> transformer) {
        this.parent = parent;
        this.transformer = transformer;
    }

    @Override
    public CompletableFuture<Latest<U>> initialValueFuture() {
        return parent.initialValueFuture().thenApply(ignored -> latest());
    }

    @Override
    public Latest<U> latest() {
        if (!closed) {
            final Latest<T> latestParent = parent.latest();
            final U transformedValue = transformer.apply(latestParent.value());
            transformedLatest = new Latest<>(latestParent.revision(), transformedValue);
        }
        return transformedLatest;
    }

    @Override
    public void close() {
        closed = true;
        // do nothing else. We don't own the parent's lifecycle
    }

    @Override
    public void watch(BiConsumer<? super Revision, ? super U> listener) {
        requireNonNull(listener, "listener");
        parent.watch(transform(listener));
    }

    @Override
    public void watch(BiConsumer<? super Revision, ? super U> listener, Executor executor) {
        requireNonNull(listener, "listener");
        requireNonNull(executor, "executor");
        parent.watch(transform(listener), executor);
    }

    private BiConsumer<Revision, T> transform(BiConsumer<? super Revision, ? super U> listener) {
        return (revision, value) -> {
            if (closed) {
                return;
            }
            final U transformedValue = transformer.apply(value);
            final boolean changed;
            if (transformedLatest == null) {
                changed = true;
            } else {
                changed = !transformedLatest.value().equals(transformedValue);
            }
            if (changed) {
                transformedLatest = new Latest<>(revision, transformedValue);
                listener.accept(revision, transformedValue);
            } // else, transformed value has not changed
        };
    }

    @Override
    public String toString() {
        return toStringHelper(this)
                .add("parent", parent)
                .add("transformer", transformer)
                .add("closed", closed)
                .toString();
    }
}
