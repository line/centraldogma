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

package com.linecorp.centraldogma.server.internal.storage.repository;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.base.MoreObjects;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

public class RepositoryCache {

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
    private final AsyncLoadingCache<CacheableCall, Object> cache;
    private final String cacheSpec;

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public RepositoryCache(String cacheSpec, MeterRegistry meterRegistry) {
        this.cacheSpec = requireNonNull(validateCacheSpec(cacheSpec), "cacheSpec");
        requireNonNull(meterRegistry, "meterRegistry");

        final Caffeine<Object, Object> builder = Caffeine.from(cacheSpec);
        if (cacheSpec.contains("maximumWeight=")) {
            builder.weigher((Weigher<CacheableCall, Object>) CacheableCall::weigh);
        }
        cache = builder.recordStats()
                       .buildAsync((key, executor) -> {
                           logger.debug("Cache miss: {}", key);
                           return key.execute();
                       });

        CaffeineCacheMetrics.monitor(meterRegistry, cache, "repository");
    }

    public <T> CompletableFuture<T> get(CacheableCall<T> call) {
        requireNonNull(call, "call");
        @SuppressWarnings("unchecked")
        final CompletableFuture<T> f = (CompletableFuture<T>) cache.get(call);
        return f;
    }

    @Nullable
    public <T> CompletableFuture<T> getIfPresent(CacheableCall<T> call) {
        requireNonNull(call, "call");
        @SuppressWarnings("unchecked")
        final CompletableFuture<T> f = (CompletableFuture<T>) cache.getIfPresent(call);
        return f;
    }

    public <T> void put(CacheableCall<T> call, T value) {
        requireNonNull(call, "call");
        requireNonNull(value, "value");
        cache.put(call, CompletableFuture.completedFuture(value));
    }

    public <T> T load(CacheableCall<T> key, Supplier<T> supplier, boolean logIfMiss) {
        CompletableFuture<T> existingFuture = getIfPresent(key);
        if (existingFuture != null) {
            final T existingValue = existingFuture.getNow(null);
            if (existingValue != null) {
                // Cached already.
                return existingValue;
            }
        }

        // Not cached yet.
        final Lock lock = key.coarseGrainedLock();
        lock.lock();
        try {
            existingFuture = getIfPresent(key);
            if (existingFuture != null) {
                final T existingValue = existingFuture.getNow(null);
                if (existingValue != null) {
                    // Other thread already put the entries to the cache before we acquire the lock.
                    return existingValue;
                }
            }

            final T value = supplier.get();
            put(key, value);
            if (logIfMiss) {
                logger.debug("Cache miss: {}", key);
            }
            return value;
        } finally {
            lock.unlock();
        }
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
