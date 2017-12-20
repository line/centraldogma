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
package com.linecorp.centraldogma.server.support.shiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.Serializable;

import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.MemoryConstrainedCacheManager;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.UnknownSessionException;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileBasedSessionDAOTest {

    @Rule
    public final TemporaryFolder rootDir = new TemporaryFolder();

    @Test
    public void testBasicOperations() throws Exception {
        final SessionDAO dao = new FileBasedSessionDAO(rootDir.getRoot().toPath());
        final Session initial = new SimpleSession();

        // A new session ID would be issued.
        final Serializable sessionId = dao.create(initial);
        assertThat(sessionId).isEqualTo(initial.getId());
        assertThat(countSessions(dao)).isOne();

        final Session session = dao.readSession(sessionId);
        assertThat(session).isEqualTo(initial);

        // Change session's last accessed time locally.
        Thread.sleep(10);
        session.touch();
        assertThat(dao.readSession(sessionId).getLastAccessTime())
                .isNotEqualTo(session.getLastAccessTime());

        // Update the session.
        dao.update(session);
        assertThat(dao.readSession(sessionId).getLastAccessTime())
                .isEqualTo(session.getLastAccessTime());

        // Delete the session.
        dao.delete(session);

        // No session exists with the specified session ID.
        assertThatThrownBy(() -> dao.readSession(sessionId))
                .isInstanceOf(UnknownSessionException.class);

        assertThat(countSessions(dao)).isZero();
    }

    @Test
    public void testCache() throws Exception {
        final FileBasedSessionDAO dao = spy(new FileBasedSessionDAO(rootDir.getRoot().toPath()));
        final MemoryConstrainedCacheManager cacheManager = new MemoryConstrainedCacheManager();
        dao.setCacheManager(cacheManager);
        final Cache<String, SimpleSession> cache = dao.cache;

        final Session initial = new SimpleSession();

        final String sessionId = (String) dao.create(initial);
        assertThat(sessionId).isEqualTo(initial.getId());
        assertThat(countSessions(dao)).isOne();

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get(sessionId)).isEqualTo(initial);

        final Session session = dao.readSession(sessionId);
        assertThat(session).isEqualTo(initial);

        // Do not perform uncached read.
        verify(dao, never()).uncachedRead(sessionId);

        // Clearing cache entries.
        cache.clear();
        assertThat(cache.size()).isEqualTo(0);
        assertThat(dao.readSession(sessionId)).isEqualTo(initial);

        // Should perform uncached read because the cache is empty.
        verify(dao, times(1)).uncachedRead(sessionId);
    }

    private static int countSessions(SessionDAO dao) {
        int count = 0;
        for (Session ignored : dao.getActiveSessions()) {
            count++;
        }
        return count;
    }
}
