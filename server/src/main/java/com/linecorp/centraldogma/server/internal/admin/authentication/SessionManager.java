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
package com.linecorp.centraldogma.server.internal.admin.authentication;

import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.server.auth.AuthenticatedSession;

/**
 * An interface for session management.
 */
public interface SessionManager extends AutoCloseable {
    /**
     * Returns a new session ID.
     */
    String generateSessionId();

    /**
     * Returns whether the session which has the specified {@code sessionId} exists.
     *
     * @param sessionId the session ID
     */
    CompletableFuture<Boolean> exists(String sessionId);

    /**
     * Returns the session which has the specified {@code sessionId}.
     *
     * @param sessionId the session ID
     */
    CompletableFuture<AuthenticatedSession> get(String sessionId);

    /**
     * Creates the specified {@code session} in the session storage.
     *
     * @param session the session to be stored
     */
    CompletableFuture<Void> create(AuthenticatedSession session);

    /**
     * Updates the specified {@code session} if it exists.
     *
     * @param session the session to be updated
     */
    CompletableFuture<Void> update(AuthenticatedSession session);

    /**
     * Deletes the specified session from the session storage.
     *
     * @param sessionId the session ID to be removed
     */
    CompletableFuture<Void> delete(String sessionId);
}
