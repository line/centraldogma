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

package com.linecorp.centraldogma.server.internal.storage.repository.cache;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.base.MoreObjects;

public final class RepositoryCache {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryCache.class);

    @Nullable
    public static String validateCacheSpec(@Nullable String cacheSpec) {
        if (cacheSpec == null) {
            return null;
        }

        try {
            CaffeineSpec.parse(cacheSpec);
            return cacheSpec;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cacheSpec: " + cacheSpec + " (" + e.getMessage() + ')');
        }
    }

    @SuppressWarnings("rawtypes")
    final AsyncLoadingCache<CacheableCall, Object> cache;
    private final String cacheSpec;

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public RepositoryCache(String cacheSpec) {
        this.cacheSpec = requireNonNull(validateCacheSpec(cacheSpec), "cacheSpec");

        final Caffeine<Object, Object> builder = Caffeine.from(cacheSpec);
        if (cacheSpec.contains("maximumWeight=")) {
            builder.weigher((Weigher<CacheableCall, Object>) CacheableCall::weigh);
        }
        cache = builder.recordStats()
                       .buildAsync((key, executor) -> {
                           logger.debug("Cache miss: {}", key);
                           return key.execute();
                       });
    }

    public CacheStats stats() {
        return cache.synchronous().stats();
    }

    public void clear() {
        cache.synchronous().invalidateAll();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("spec", cacheSpec)
                          .add("stats", stats())
                          .toString();
    }
}
