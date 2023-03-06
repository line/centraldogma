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

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig.Host;

import com.jcraft.jsch.Session;

import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredential;

final class Git6WithAuth extends GitWithAuth {
    Git6WithAuth(GitMirror mirror, File repoDir) throws IOException {
        super(mirror, repoDir);
    }

    @Override
    FetchCommand fetch(int depth) {
        return fetch().setDepth(1);
    }

    @Override
    public <T extends TransportCommand<?, ?>> void configureSsh(T cmd, PublicKeyMirrorCredential cred) {
        cmd.setTransportConfigCallback(transport -> {
            final SshTransport sshTransport = (SshTransport) transport;
            sshTransport.setSshSessionFactory(new JschConfigSessionFactory() {
                @Override
                protected void configure(Host host, Session session) {
                    try {
                        session.setHostKeyRepository(
                                new MirrorHostKeyRepository(getMirror().localRepo().parent().metaRepo()));
                        session.setIdentityRepository(new MirrorIdentityRepository(
                                cred.username() + '@' + host.getHostName(), cred));
                    } catch (MirrorException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new MirrorException(e);
                    }
                }
            });
        });
    }

    @Override
    public  <T extends TransportCommand<?, ?>> void configureSsh(T cmd, PasswordMirrorCredential cred) {
        cmd.setTransportConfigCallback(transport -> {
            final SshTransport sshTransport = (SshTransport) transport;
            sshTransport.setSshSessionFactory(new JschConfigSessionFactory() {
                @Override
                protected void configure(Host host, Session session) {
                    try {
                        session.setHostKeyRepository(
                                new MirrorHostKeyRepository(getMirror().localRepo().parent().metaRepo()));
                        session.setPassword(cred.password());
                    } catch (MirrorException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new MirrorException(e);
                    }
                }
            });
        });
    }
}
