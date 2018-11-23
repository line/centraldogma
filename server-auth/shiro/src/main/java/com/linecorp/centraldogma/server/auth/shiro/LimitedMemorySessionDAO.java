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
package com.linecorp.centraldogma.server.auth.shiro;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.time.Duration;
import java.util.Collection;
import java.util.function.Supplier;

import org.apache.shiro.session.Session;
import org.apache.shiro.session.UnknownSessionException;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.eis.MemorySessionDAO;
import org.apache.shiro.session.mgt.eis.SessionDAO;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * A {@link SessionDAO} which stores {@link Session}s in the memory. Unlike {@link MemorySessionDAO},
 * its memory usage is limited to the configured size.
 */
final class LimitedMemorySessionDAO implements SessionDAO {

    private final Supplier<String> sessionIdGenerator;
    private final Cache<Serializable, Session> cache;

    /**
     * Creates a new instance.
     *
     * @param sessionIdGenerator the session ID generator
     * @param maximumSize the maximum number of sessions can be stored in the cache
     * @param maximumDuration the maximum duration which the session stays in the cache since entering
     */
    LimitedMemorySessionDAO(Supplier<String> sessionIdGenerator, int maximumSize, Duration maximumDuration) {
        this.sessionIdGenerator = requireNonNull(sessionIdGenerator, "sessionIdGenerator");
        checkArgument(maximumSize > 0, "maximumSize: %s (expected: > 0)", maximumSize);
        requireNonNull(maximumDuration, "maximumDuration");
        cache = Caffeine.newBuilder()
                        .expireAfterWrite(maximumDuration)
                        .maximumSize(maximumSize)
                        .build();
    }

    @Override
    public Serializable create(Session session) {
        final SimpleSession simpleSession = ensureSimpleSession(session);
        final String id = sessionIdGenerator.get();
        simpleSession.setId(id);
        cache.put(id, simpleSession);
        return session.getId();
    }

    @Override
    public Session readSession(Serializable sessionId) {
        if (sessionId == null) {
            throw new UnknownSessionException("sessionId is null");
        }

        final Session session = cache.getIfPresent(sessionId);
        if (session != null) {
            return session;
        }
        throw new UnknownSessionException(sessionId.toString());
    }

    @Override
    public void update(Session session) {
        final SimpleSession simpleSession = ensureSimpleSession(session);
        readSession(simpleSession.getId());
        cache.put(simpleSession.getId(), simpleSession);
    }

    @Override
    public void delete(Session session) {
        cache.invalidate(session.getId());
    }

    @Override
    public Collection<Session> getActiveSessions() {
        return cache.asMap().values();
    }

    private static SimpleSession ensureSimpleSession(Session session) {
        requireNonNull(session, "session");
        checkArgument(session instanceof SimpleSession,
                      "session: %s (expected: SimpleSession)", session);
        return (SimpleSession) session;
    }
}
