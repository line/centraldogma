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

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.linecorp.centraldogma.common.Revision;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AsyncMappingWatcher<T, U> extends AbstractMappingWatcher<T, U> {

    private static final Logger logger = LoggerFactory.getLogger(AsyncMappingWatcher.class);

    static <T, U> AsyncMappingWatcher<T, U> of(Watcher<T> parent,
                                               Function<? super T, ? extends CompletableFuture<? extends U>>
                                                       mapper,
                                               boolean closeParentWhenClosing) {
        requireNonNull(parent, "parent");
        requireNonNull(mapper, "mapper");
        return new AsyncMappingWatcher<>(parent, mapper, closeParentWhenClosing);
    }

    private final Function<? super T, ? extends CompletableFuture<? extends U>> mapper;
    private final AtomicReference<@Nullable Latest<U>> mappedLatest = new AtomicReference<>();

    private static <U> boolean isUpdate(Latest<U> newLatest, @Nullable Latest<U> existing) {
        if (existing == null) {
            return true;
        }
        if (Objects.equals(existing.value(), newLatest.value())) {
            return false;
        }
        return newLatest.revision().compareTo(existing.revision()) >= 0;
    }

    AsyncMappingWatcher(Watcher<T> parent, Function<? super T, ? extends CompletableFuture<? extends U>> mapper,
                        boolean closeParentWhenClosing) {
        super(parent, closeParentWhenClosing);
        this.mapper = mapper;
        parent.initialValueFuture().exceptionally(cause -> {
            initialValueFuture.completeExceptionally(cause);
            return null;
        });
        final BiConsumer<Throwable, Revision> reportFailure = (e, r) -> {
            logger.warn("Unexpected exception is raised from mapper.apply(). mapper: {}, revision {}", mapper, r, e);
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
                reportFailure.accept(e, revision);
                return;
            }
            mappedValueFuture.whenComplete((mappedValue, e) -> {
                if (closed) {
                    return;
                }
                if (null != e) {
                    reportFailure.accept(e, revision);
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
                notifyListeners(newLatest, null);
                if (!initialValueFuture.isDone()) {
                    initialValueFuture.complete(newLatest);
                }
            });
        });
    }

    @Override
    public String toString() {
        return toStringHelper(this)
                .add("parent", parent)
                .add("mapper", mapper)
                .add("closed", closed)
                .toString();
    }

    @Override
    protected @Nullable Latest<U> mappedLatest() {
        return mappedLatest.get();
    }
}
