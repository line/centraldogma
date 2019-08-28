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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Revision;

public class TransformingWatcher<T, U> implements Watcher<U> {

    protected final Watcher<T> parent;
    private final Function<T, U> transformer;
    @Nullable
    private volatile Latest<U> transformedLatest;
    protected volatile boolean closed;

    public TransformingWatcher(Watcher<T> parent, Function<T, U> transformer) {
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
            Latest<T> latestParent = parent.latest();
            U transformedValue = transformer.apply(latestParent.value());
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
        parent.watch((rev, parentValue) -> {
            if (closed) {
                return;
            }
            final U transformedValue = transformer.apply(parentValue);
            Optional<U> unchanged = Optional
                    .ofNullable(transformedLatest)
                    .map(Latest::value)
                    .filter(transformedValue::equals);
            if (!unchanged.isPresent()) {
                transformedLatest = new Latest<>(rev, transformedValue);
                listener.accept(rev, transformedValue);
            } // else, transformed value has not changed
        });
    }

    @Override
    public String toString() {
        String me = getClass().getSimpleName() + " filtering " + parent + " with " + transformer;
        if (closed) {
            return me + " (closed)";
        }
        return me;
    }
}
