/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.server.internal.mirror.SshGitMirror.createSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.common.file.nonefs.NoneFileSystemFactory;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;

class SshGitMirrorTest {

    @Test
    void sessionIsClosedIfExceptionIsRaised() throws URISyntaxException {
        final SshClient client = client();
        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        client.addSessionListener(new SessionListener() {
            @Override
            public void sessionEstablished(Session session) {
                sessionRef.set(session);
            }
        });
        client.start();

        assertThatThrownBy(() ->
            createSession(client, new URIish("https://github.com/line/centraldogma-authtest.git"))
        ).isExactlyInstanceOf(RuntimeException.class);
        final Session session = sessionRef.get();
        assertThat(session).isNotNull();
        assertThat(session.isClosed()).isTrue();
    }

    private static SshClient client() {
        final ClientBuilder builder = ClientBuilder.builder();
        // Do not use local file system.
        builder.hostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        builder.fileSystemFactory(NoneFileSystemFactory.INSTANCE);
        // Do not verify the server key.
        builder.serverKeyVerifier((clientSession, remoteAddress, serverKey) -> true);
        return builder.build();
    }
}
