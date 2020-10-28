/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.apache.curator.framework.recipes.shared.SharedCountListener;
import org.apache.curator.framework.recipes.shared.SharedCountReader;
import org.apache.curator.framework.recipes.shared.VersionedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SettableSharedCount implements SharedCountReader {

    private static final Logger logger = LoggerFactory.getLogger(SettableSharedCount.class);

    private final List<SharedCountListener> listeners;
    private int count;

    SettableSharedCount(int count) {
        this.count = count;
        listeners = new ArrayList<>();
    }

    void setCount(int count) {
        this.count = count;
        for (SharedCountListener listener : listeners) {
            try {
                listener.countHasChanged(this, count);
            } catch (Exception e) {
                logger.warn("Unexpected exception caught while notifying {}", listener, e);
            }
        }
    }

    @Override
    public int getCount() {
        return count;
    }

    @Override
    public VersionedValue<Integer> getVersionedValue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addListener(SharedCountListener listener) {
        listeners.add(listener);
    }

    @Override
    public void addListener(SharedCountListener listener, Executor executor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeListener(SharedCountListener listener) {
        throw new UnsupportedOperationException();
    }
}
