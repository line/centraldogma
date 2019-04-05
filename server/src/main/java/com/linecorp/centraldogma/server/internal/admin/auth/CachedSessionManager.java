/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.admin.auth;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.github.benmanes.caffeine.cache.Cache;

import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.auth.SessionManager;

/**
 * Memory cache based {@link SessionManager} implementation.
 */
public final class CachedSessionManager extends ForwardingSessionManager {

    private final Cache<String, Session> cache;

    /**
     * Creates a new {@link SessionManager} instance which relies on the memory cache.
     *
     * @param delegate the {@link SessionManager} which manages the sessions using any kind of permanent
     *                 storage
     * @param cache the {@link Cache} instance which is configured by the caller
     */
    public CachedSessionManager(SessionManager delegate, Cache<String, Session> cache) {
        super(delegate);
        this.cache = requireNonNull(cache, "cache");
    }

    @Override
    public CompletableFuture<Boolean> exists(String sessionId) {
        requireNonNull(sessionId, "sessionId");
        if (cache.getIfPresent(sessionId) != null) {
            return CompletableFuture.completedFuture(true);
        }
        return super.exists(sessionId);
    }

    @Override
    public CompletableFuture<Session> get(String sessionId) {
        requireNonNull(sessionId, "sessionId");
        final Session session = cache.getIfPresent(sessionId);
        if (session != null) {
            return CompletableFuture.completedFuture(session);
        }
        return super.get(sessionId).thenApply(found -> {
            if (found != null) {
                cache.put(sessionId, found);
            }
            return found;
        });
    }

    @Override
    public CompletableFuture<Void> create(Session session) {
        return super.create(session).thenApply(unused -> {
            cache.put(session.id(), session);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> update(Session session) {
        return super.update(session).thenApply(unused -> {
            cache.put(session.id(), session);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> delete(String sessionId) {
        return super.delete(sessionId).thenApply(unused -> {
            cache.invalidate(sessionId);
            return null;
        });
    }
}
