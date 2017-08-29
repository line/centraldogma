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

package com.linecorp.centraldogma.server.repository.git;

import java.util.LinkedHashMap;
import java.util.Map;

final class LruMap<K, V> extends LinkedHashMap<K, V> {

    private static final long serialVersionUID = 8533922512014170929L;

    static <K, V> ThreadLocal<LruMap<K, V>> newThreadLocal(int maxEntries) {
        return ThreadLocal.withInitial(() -> new LruMap<>(maxEntries));
    }

    private final int maxEntries;

    private LruMap(int maxEntries) {
        super(maxEntries, 0.75f, true);
        this.maxEntries = maxEntries;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxEntries;
    }
}
