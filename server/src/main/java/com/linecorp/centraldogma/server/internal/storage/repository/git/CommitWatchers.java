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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.common.Revision;

final class CommitWatchers {

    private static final Logger logger = LoggerFactory.getLogger(CommitWatchers.class);

    private final Map<PathPatternFilter, Set<Watch>> watchesMap = new WatcherMap(8192);

    void add(Revision lastKnownRev, PathPatternFilter pathPattern, CompletableFuture<Revision> future) {
        add0(pathPattern, new Watch(lastKnownRev, future));
    }

    private void add0(final PathPatternFilter pathPattern, Watch watch) {
        synchronized (watchesMap) {
            final Set<Watch> watches =
                    watchesMap.computeIfAbsent(pathPattern,
                                               k -> Collections.newSetFromMap(new IdentityHashMap<>()));
            watches.add(watch);
        }

        watch.future.whenComplete((revision, cause) -> {
            if (watch.removed) {
                return;
            }

            // Remove manually only when the watch was not removed from the set successfully.
            // This usually happens when a user cancels the promise.
            synchronized (watchesMap) {
                watchesMap.get(pathPattern).remove(watch);
            }
        });
    }

    void notify(Revision revision, String path) {
        List<Watch> eligibleWatches = null;
        synchronized (watchesMap) {
            for (final Entry<PathPatternFilter, Set<Watch>> e : watchesMap.entrySet()) {
                if (!e.getKey().matches(path)) {
                    continue;
                }

                final Set<Watch> watches = e.getValue();
                for (Iterator<Watch> i = watches.iterator(); i.hasNext();) {
                    final Watch w = i.next();
                    final Revision lastKnownRevision = w.lastKnownRevision;
                    if (lastKnownRevision.compareTo(revision) < 0) {
                        eligibleWatches = move(eligibleWatches, i, w);
                    } else {
                        logIneligibleFuture(lastKnownRevision, revision);
                    }
                }
            }
        }

        if (eligibleWatches == null) {
            return;
        }

        // Notify the matching promises found above.
        final int numEligiblePromises = eligibleWatches.size();
        for (int i = 0; i < numEligiblePromises; i++) {
            eligibleWatches.get(i).future.complete(revision);
        }
    }

    private static List<Watch> move(List<Watch> watches, Iterator<Watch> i, Watch w) {

        i.remove();
        w.removed = true;

        if (watches == null) {
            watches = new ArrayList<>();
        }

        watches.add(w);
        return watches;
    }

    private static void logIneligibleFuture(Revision lastKnownRevision, Revision newRevision) {
        logger.debug("Not notifying a future with same or newer lastKnownRevision: {} (newRevision: {})",
                     lastKnownRevision, newRevision);
    }

    private static final class WatcherMap
            extends LinkedHashMap<PathPatternFilter, Set<Watch>> {

        private static final long serialVersionUID = 6793455658134063005L;

        private final int maxEntries;

        WatcherMap(int maxEntries) {
            super(maxEntries, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Entry<PathPatternFilter, Set<Watch>> eldest) {
            // Remove only the entries with empty watchers.
            return size() > maxEntries && eldest.getValue().isEmpty();
        }
    }

    private static final class Watch {

        final Revision lastKnownRevision;
        final CompletableFuture<Revision> future;
        volatile boolean removed;

        Watch(Revision lastKnownRevision, CompletableFuture<Revision> future) {
            this.lastKnownRevision = lastKnownRevision;
            this.future = future;
        }
    }
}
