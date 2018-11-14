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

import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.auth.Session;

/**
 * A {@link SessionManager} which forwards all its method calls to another {@link SessionManager}.
 */
public class ForwardingSessionManager implements SessionManager {

    private final SessionManager delegate;

    /**
     * Creates a new {@link SessionManager} instance which forwards all its method calls to the specified
     * {@code delegate}.
     */
    protected ForwardingSessionManager(SessionManager delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    protected final <T extends SessionManager> T delegate() {
        return Util.unsafeCast(delegate);
    }

    @Override
    public String generateSessionId() {
        return delegate().generateSessionId();
    }

    @Override
    public CompletableFuture<Boolean> exists(String sessionId) {
        return delegate().exists(sessionId);
    }

    @Override
    public CompletableFuture<Session> get(String sessionId) {
        return delegate().get(sessionId);
    }

    @Override
    public CompletableFuture<Void> create(Session session) {
        return delegate().create(session);
    }

    @Override
    public CompletableFuture<Void> update(Session session) {
        return delegate().update(session);
    }

    @Override
    public CompletableFuture<Void> delete(String sessionId) {
        return delegate().delete(sessionId);
    }

    @Override
    public void close() throws Exception {
        delegate().close();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '(' + delegate() + ')';
    }
}
