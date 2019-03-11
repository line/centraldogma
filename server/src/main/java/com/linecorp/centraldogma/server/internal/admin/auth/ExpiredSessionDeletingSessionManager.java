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

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.auth.SessionManager;

/**
 * A {@link SessionManager} which deletes expired sessions while getting them.
 */
public final class ExpiredSessionDeletingSessionManager extends ForwardingSessionManager {

    public ExpiredSessionDeletingSessionManager(SessionManager delegate) {
        super(delegate);
    }

    @Override
    public CompletableFuture<Session> get(String sessionId) {
        return super.get(sessionId).thenApply(session -> {
            if (session != null) {
                if (Instant.now().isBefore(session.expirationTime())) {
                    return session;
                }

                // Delete the expired session and return null.
                delete(sessionId);
            }
            return null;
        });
    }
}
