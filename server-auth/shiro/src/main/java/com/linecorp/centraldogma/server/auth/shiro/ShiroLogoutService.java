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

package com.linecorp.centraldogma.server.auth.shiro;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.DefaultSessionKey;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.auth.SessionManager;
import com.linecorp.centraldogma.server.internal.admin.service.DefaultLogoutService;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

/**
 * A service to handle a logout request to Central Dogma Web admin service.
 */
final class ShiroLogoutService extends DefaultLogoutService {

    private static final Logger logger = LoggerFactory.getLogger(ShiroLogoutService.class);

    private final SecurityManager securityManager;

    ShiroLogoutService(SecurityManager securityManager,
                       Function<String, CompletableFuture<Void>> logoutSessionPropagator,
                       Supplier<Boolean> sessionPropagatorWritableChecker, SessionManager sessionManager,
                       boolean tlsEnabled, EncryptionStorageManager encryptionStorageManager) {
        super(logoutSessionPropagator, sessionPropagatorWritableChecker,
              sessionManager, tlsEnabled, encryptionStorageManager);
        this.securityManager = requireNonNull(securityManager, "securityManager");
    }

    @Override
    protected CompletableFuture<Void> invalidateSession(ServiceRequestContext ctx, String sessionId) {
        return CompletableFuture.runAsync(() -> {
            // Need to set the thread-local security manager to silence
            // the UnavailableSecurityManagerException logged at DEBUG level.
            ThreadContext.bind(securityManager);
            try {
                final Session session = securityManager.getSession(new DefaultSessionKey(sessionId));
                if (session != null) {
                    final Subject currentUser = new Subject.Builder(securityManager)
                            .sessionCreationEnabled(false)
                            .sessionId(sessionId)
                            .buildSubject();

                    currentUser.logout();
                }
            } catch (Throwable t) {
                logger.warn("{} Failed to log out: {}", ctx, sessionId, t);
            } finally {
                ThreadContext.unbindSecurityManager();
            }
        }, ctx.blockingTaskExecutor());
    }
}
