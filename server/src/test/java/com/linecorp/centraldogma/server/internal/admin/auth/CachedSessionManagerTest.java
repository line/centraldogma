/*
 * Copyright 2020 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.auth.SessionManager;

class CachedSessionManagerTest {

    @Test
    void shouldOperateWithCache() {
        final Session session =
                new Session("id", "username", Duration.ofHours(1));

        final Cache<String, Session> cache = spy(Caffeine.newBuilder().build());

        final SessionManager delegate = mock(SessionManager.class);
        when(delegate.create(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(delegate.update(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(delegate.delete(any())).thenReturn(CompletableFuture.completedFuture(null));

        final CachedSessionManager manager = new CachedSessionManager(delegate, cache);

        manager.create(session).join();
        verify(cache).put(session.id(), session);

        assertThat(manager.get(session.id()).join()).isEqualTo(session);
        verify(cache).getIfPresent(session.id());

        final Session updatedSession =
                new Session("id", "username", Duration.ofHours(1));

        manager.update(updatedSession).join();
        verify(cache).put(updatedSession.id(), updatedSession);

        // Called 2 times with the same ID.
        assertThat(manager.get(updatedSession.id()).join()).isEqualTo(updatedSession);
        verify(cache, times(2)).getIfPresent(updatedSession.id());

        manager.delete(updatedSession.id()).join();
        verify(cache).invalidate(updatedSession.id());
    }
}
