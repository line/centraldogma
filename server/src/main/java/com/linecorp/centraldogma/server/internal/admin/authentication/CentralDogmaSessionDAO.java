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

package com.linecorp.centraldogma.server.internal.admin.authentication;

import static com.linecorp.centraldogma.server.internal.admin.authentication.SessionSerializationUtil.deserialize;
import static com.linecorp.centraldogma.server.internal.admin.authentication.SessionSerializationUtil.serialize;
import static com.linecorp.centraldogma.server.internal.command.Command.push;
import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.INTERNAL_PROJECT_NAME;
import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.SESSION_REPOSITORY_NAME;
import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.cache.CacheManager;
import org.apache.shiro.cache.CacheManagerAware;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.UnknownSessionException;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.RedundantChangeException;

/**
 * A session data access object(DAO) which uses Central Dogma as its storage.
 */
public class CentralDogmaSessionDAO implements SessionDAO, CacheManagerAware {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaSessionDAO.class);

    @VisibleForTesting
    static final String CACHE_NAME = CentralDogmaSessionDAO.class.getSimpleName();

    private static final String ID_PREFIX = "sessId-";

    private final ProjectManager pm;
    private final CommandExecutor executor;

    private CacheManager cacheManager;

    public CentralDogmaSessionDAO(ProjectManager pm, CommandExecutor executor) {
        this.pm = requireNonNull(pm, "pm");
        this.executor = requireNonNull(executor, "executor");
    }

    /**
     * Sets the available {@link CacheManager} instance.
     */
    @Override
    public void setCacheManager(CacheManager cacheManager) {
        this.cacheManager = requireNonNull(cacheManager, "cacheManager");
    }

    /**
     * Creates a new {@link Session} with a new session ID.
     */
    @Override
    public Serializable create(Session session) {
        ensureNotInEventLoop();
        final SimpleSession simpleSession = ensureSimpleSession(session);
        final Serializable sessionId = ID_PREFIX + UUID.randomUUID();
        simpleSession.setId(sessionId);
        return createOrUpdate(sessionId, simpleSession);
    }

    /**
     * Creates or updates the specified {@link SimpleSession}.
     */
    private Serializable createOrUpdate(Serializable sessionId, SimpleSession session) {
        try {
            // The current user attribute has not been attached before login is completed.
            // The actual requesting username might be set as the request context attribute,
            // but it is worthless.
            final User currentUser = AuthenticationUtil.currentUser();
            final String username = currentUser != null ? currentUser.name() : "<unknown>";
            final String value = serialize(session);
            executor.execute(
                    push(Author.SYSTEM, INTERNAL_PROJECT_NAME, SESSION_REPOSITORY_NAME, Revision.HEAD,
                         "Login: " + username, "",
                         Markup.PLAINTEXT, Change.ofTextUpsert(sessionPath(sessionId), value)))
                    .thenRun(() -> {
                        if (cacheManager != null) {
                            cacheManager.getCache(CACHE_NAME).put(sessionId, session);
                        }
                    })
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            if (!(e.getCause() instanceof RedundantChangeException)) {
                throw new AuthenticationException("Session creation failure", cause(e));
            }
        }
        return sessionId;
    }

    /**
     * Returns a {@link Session} belonging to the specified session ID.
     *
     * @throws UnknownSessionException if there is no session belonging to the specified session ID.
     */
    @Override
    public Session readSession(Serializable sessionId) {
        if (!(sessionId instanceof String) ||
            !((String) sessionId).startsWith(ID_PREFIX)) {
            throw new UnknownSessionException(sessionId.toString());
        }
        ensureNotInEventLoop();
        if (cacheManager != null) {
            final SimpleSession session = (SimpleSession) cacheManager.getCache(CACHE_NAME).get(sessionId);
            if (session != null) {
                return session;
            }
        }
        final SimpleSession session = readSession0(sessionId);
        if (cacheManager != null) {
            cacheManager.getCache(CACHE_NAME).put(sessionId, session);
        }
        return session;
    }

    /**
     * Returns a {@link SimpleSession} belonging to the specified session ID.
     */
    @VisibleForTesting
    SimpleSession readSession0(Serializable sessionId) {
        try {
            return pm.get(INTERNAL_PROJECT_NAME).repos()
                     .get(SESSION_REPOSITORY_NAME)
                     .get(Revision.HEAD, sessionPath(sessionId))
                     .thenApply(entry -> deserialize((String) entry.content()))
                     .get();
        } catch (InterruptedException | ExecutionException e) {
            throw new UnknownSessionException(sessionId.toString(), cause(e));
        }
    }

    /**
     * Updates the specified {@link Session}.
     */
    @Override
    public void update(Session session) {
        ensureNotInEventLoop();
        final SimpleSession simpleSession = ensureSimpleSession(session);
        createOrUpdate(simpleSession.getId(), simpleSession);
    }

    /**
     * Deletes the specified {@link Session}.
     */
    @Override
    public void delete(Session session) {
        ensureNotInEventLoop();
        executor.execute(
                push(Author.SYSTEM, INTERNAL_PROJECT_NAME, SESSION_REPOSITORY_NAME, Revision.HEAD,
                     "Logout: " + AuthenticationUtil.currentUser(), "",
                     Markup.PLAINTEXT, Change.ofRemoval(sessionPath(session.getId()))))
                .thenRun(() -> {
                    if (cacheManager != null) {
                        cacheManager.getCache(CACHE_NAME).remove(session.getId());
                    }
                });
    }

    /**
     * Returns every active {@link Session} stored in this Central Dogma storage.
     */
    @Override
    public Collection<Session> getActiveSessions() {
        ensureNotInEventLoop();
        try {
            logger.debug("Starting to collect active sessions ..");
            return pm.get(INTERNAL_PROJECT_NAME).repos()
                     .get(SESSION_REPOSITORY_NAME).find(Revision.HEAD, "/*")
                     .thenApply(entries -> {
                         final Collection<Session> sessions = new ArrayList<>();
                         final List<Change<?>> changesOfRemoval = new ArrayList<>();
                         for (Map.Entry<String, Entry<?>> entry : entries.entrySet()) {
                             try {
                                 final SimpleSession session = deserialize((String) entry.getValue().content());
                                 sessions.add(session);
                                 logger.debug("Active session: {}", session);
                             } catch (Exception e) {
                                 // Invalid serialization format.
                                 changesOfRemoval.add(Change.ofRemoval(entry.getKey()));
                             }
                         }
                         removeInvalidSessions(changesOfRemoval);
                         return sessions;
                     })
                     .get();
        } catch (InterruptedException | ExecutionException e) {
            logger.warn("Failed to collect active sessions", cause(e));
            return ImmutableList.of();
        } finally {
            logger.debug("Finished collecting active sessions");
        }
    }

    private void removeInvalidSessions(List<Change<?>> changesOfRemoval) {
        if (changesOfRemoval != null && !changesOfRemoval.isEmpty()) {
            try {
                executor.execute(
                        push(Author.SYSTEM, INTERNAL_PROJECT_NAME, SESSION_REPOSITORY_NAME,
                             Revision.HEAD, "Logout", "", Markup.PLAINTEXT,
                             changesOfRemoval));
            } catch (Exception e) {
                logger.warn("Failed to remove invalid formatted sessions", cause(e));
            }
        }
    }

    private static void ensureNotInEventLoop() {
        assert !RequestContext.current().eventLoop().inEventLoop();
    }

    private static SimpleSession ensureSimpleSession(Session session) {
        if (session instanceof SimpleSession) {
            return (SimpleSession) session;
        }

        throw new IllegalArgumentException("Session '" + session.getClass().getName() + "' is not supported.");
    }

    private static String sessionPath(Serializable sessionId) {
        return "/" + sessionId + ".txt";
    }

    private static Throwable cause(Throwable throwable) {
        final Throwable cause = throwable.getCause();
        return cause != null ? cause : throwable;
    }
}
