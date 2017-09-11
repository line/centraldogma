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

import static com.linecorp.centraldogma.server.support.shiro.CentralDogmaSessionDAO.CACHE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.ForkJoinPool;

import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.MemoryConstrainedCacheManager;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.UnknownSessionException;
import org.apache.shiro.session.mgt.SimpleSession;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.project.ProjectManager;

import io.netty.channel.EventLoop;

public class CentralDogmaSessionDAOTest {

    @ClassRule
    public static final TemporaryFolder rootDir = new TemporaryFolder();

    private static RequestContext ctx;

    @BeforeClass
    public static void setup() {
        // Need to setup RequestContext and EventLoop for CentralDogmaSessionDAO#ensureNotInEventLoop.
        final EventLoop eventLoop = mock(EventLoop.class);
        ctx = mock(RequestContext.class);
        when(ctx.eventLoop()).thenReturn(eventLoop);
    }

    @Test
    public void testBasicOperations() throws IOException {
        final ProjectManager pm = new DefaultProjectManager(
                rootDir.newFolder(), ForkJoinPool.commonPool(), null);
        final CommandExecutor executor = new StandaloneCommandExecutor(pm, ForkJoinPool.commonPool());
        executor.start(null, null);

        final CentralDogmaSessionDAO dao = new CentralDogmaSessionDAO(pm, executor);
        try (SafeCloseable ignored = RequestContext.push(ctx, false)) {
            final SimpleSession initial = new SimpleSession();

            // A new session ID would be issued in CentralDogmaSessionDAO#create.
            final Serializable sessionId = dao.create(initial);
            assertThat(sessionId).isEqualTo(initial.getId());
            assertThat(dao.getActiveSessions().size()).isEqualTo(1);

            final Session session = dao.readSession(sessionId);
            assertThat(session).isEqualTo(initial);

            // Changing session's last accessed time locally.
            session.touch();
            assertThat(dao.readSession(sessionId).getLastAccessTime())
                    .isNotEqualTo(session.getLastAccessTime());

            // Updating it to CentralDogma storage.
            dao.update(session);
            assertThat(dao.readSession(sessionId).getLastAccessTime())
                    .isEqualTo(session.getLastAccessTime());

            dao.delete(session);
            // CentralDogmaSessionDAO#delete does not block this thread. So we need to wait until the operation
            // is completed.
            await().until(() -> {
                try (SafeCloseable ignored2 = RequestContext.push(ctx, false)) {
                    return dao.getActiveSessions().isEmpty();
                }
            });

            // No session exists with the specified session ID.
            assertThatThrownBy(() -> dao.readSession(sessionId))
                    .isInstanceOf(UnknownSessionException.class);
        } finally {
            executor.stop();
        }
    }

    @Test
    public void testCache() throws IOException {
        final ProjectManager pm = new DefaultProjectManager(
                rootDir.newFolder(), ForkJoinPool.commonPool(), null);
        final CommandExecutor executor = new StandaloneCommandExecutor(pm, ForkJoinPool.commonPool());
        executor.start(null, null);

        final CentralDogmaSessionDAO dao = spy(new CentralDogmaSessionDAO(pm, executor));
        final MemoryConstrainedCacheManager cacheManager = new MemoryConstrainedCacheManager();
        final Cache<String, SimpleSession> cache = cacheManager.getCache(CACHE_NAME);
        dao.setCacheManager(cacheManager);

        try (SafeCloseable ignored = RequestContext.push(ctx, false)) {
            final SimpleSession initial = new SimpleSession();

            final Serializable sessionId = dao.create(initial);
            assertThat(sessionId).isEqualTo(initial.getId());
            assertThat(dao.getActiveSessions().size()).isEqualTo(1);

            assertThat(cache.size()).isEqualTo(1);
            assertThat(cache.get(sessionId.toString())).isEqualTo(initial);

            final Session session = dao.readSession(sessionId);
            assertThat(session).isEqualTo(initial);

            // Do not access CentralDogma storage.
            verify(dao, times(0)).readSession0(sessionId);

            // Clearing cache entries.
            cache.clear();
            assertThat(cache.size()).isEqualTo(0);
            assertThat(dao.readSession(sessionId)).isEqualTo(initial);

            // Should access CentralDogma storage because the cache is empty.
            verify(dao, times(1)).readSession0(sessionId);
        } finally {
            executor.stop();
        }
    }
}
