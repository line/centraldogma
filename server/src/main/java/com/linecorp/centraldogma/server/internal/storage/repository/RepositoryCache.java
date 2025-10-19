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

import org.jspecify.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.github.benmanes.caffeine.cache.Weigher;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.centraldogma.server.storage.repository.CacheableCall;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

public final class RepositoryCache {

    public static final Logger logger = LoggerFactory.getLogger(RepositoryCache.class);

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
    private final AsyncCache<CacheableCall, Object> cache;
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
                       .buildAsync();

        CaffeineCacheMetrics.monitor(meterRegistry, cache, "repository");
    }

    public <T> CompletableFuture<T> get(CacheableCall<T> call) {
        requireNonNull(call, "call");
        final CompletableFuture<T> future = new CompletableFuture<>();
        //noinspection unchecked
        final CompletableFuture<T> prior =
                (CompletableFuture<T>) cache.asMap().putIfAbsent(call, (CompletableFuture<Object>) future);
        if (prior != null) {
            return prior;
        }

        call.execute().handle((result, cause) -> {
            try (SafeCloseable ignored = RequestContextUtil.pop()) {
                if (cause != null) {
                    future.completeExceptionally(cause);
                } else {
                    future.complete(result);
                }
            }
            return null;
        });
        return future;
    }

    public void clear() {
        cache.synchronous().invalidateAll();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("spec", cacheSpec)
                          .add("stats", cache.synchronous().stats())
                          .toString();
    }
}
