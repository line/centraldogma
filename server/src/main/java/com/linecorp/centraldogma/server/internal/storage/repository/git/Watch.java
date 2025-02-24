/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Revision;

final class Watch {

    final Revision lastKnownRevision;
    @Nullable
    private final CompletableFuture<Revision> future;
    @Nullable
    private final WatchListener listener;
    private boolean shouldRemove;
    private volatile boolean removed;

    Watch(Revision lastKnownRevision,
          @Nullable CompletableFuture<Revision> future,
          @Nullable WatchListener listener) {
        this.lastKnownRevision = lastKnownRevision;
        assert (future != null && listener == null) || (future == null && listener != null);
        this.future = future;
        this.listener = listener;
        shouldRemove = future != null;
    }

    void notify(Revision revision) {
        if (future != null) {
            future.complete(revision);
        } else {
            assert listener != null;
            // The watch will be removed in the next notification if shouldRemove is set to true.
            shouldRemove = listener.onUpdate(revision, null);
        }
    }

    void notifyFailure(Throwable cause) {
        if (future != null) {
            future.completeExceptionally(cause);
        } else {
            assert listener != null;
            shouldRemove = listener.onUpdate(null, cause);
        }
    }

    @Nullable
    CompletableFuture<Revision> future() {
        return future;
    }

    boolean shouldRemove() {
        return shouldRemove;
    }

    void remove() {
        removed = true;
    }

    boolean wasRemoved() {
        return removed;
    }

    @FunctionalInterface
    interface WatchListener {
        /**
         * Invoked when the {@link Watch} is notified of an update.
         * Returns {@code true} if the {@link Watch} should be removed from the watch list.
         */
        boolean onUpdate(@Nullable Revision revision, @Nullable Throwable cause);
    }
}
