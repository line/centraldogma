/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.centraldogma.testing;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.centraldogma.client.Latest;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.common.Revision;

public final class SettableWatcher<T> implements Watcher<T> {
    private volatile T value;
    private final List<BiConsumer<? super Revision, ? super T>> updateListeners = new CopyOnWriteArrayList<>();

    @Override
    public ScheduledExecutorService watchScheduler() {
        return CommonPools.blockingTaskExecutor();
    }

    @Override
    public CompletableFuture<Latest<T>> initialValueFuture() {
        return CompletableFuture.completedFuture(latest());
    }

    @Override
    public Latest<T> latest() {
        return new Latest<>(Revision.HEAD, value);
    }

    @Override
    public void close() {
        updateListeners.clear();
    }

    public void setValue(T value) {
        this.value = value;
        if (this.value != null) {
            for (BiConsumer<? super Revision, ? super T> l : updateListeners) {
                l.accept(Revision.HEAD, value);
            }
        }
    }

    @Override
    public void watch(BiConsumer<? super Revision, ? super T> listener) {
        updateListeners.add(listener);
        if (value != null) {
            listener.accept(Revision.HEAD, value);
        }
    }

    @Override
    public void watch(BiConsumer<? super Revision, ? super T> listener, Executor executor) {
        watch(listener);
    }
}
