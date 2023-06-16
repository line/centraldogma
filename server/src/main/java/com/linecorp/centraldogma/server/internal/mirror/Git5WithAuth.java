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
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig;
import org.eclipse.jgit.transport.OpenSshConfig.Host;
import org.eclipse.jgit.transport.SshTransport;

import com.jcraft.jsch.Session;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredential;

final class Git5WithAuth extends GitWithAuth {

    private static final OpenSshConfig EMPTY_CONFIG = emptySshConfig();

    @Nullable
    private static final Method SET_CONFIG_METHOD;

    static {
        Method setConfigMethod = null;
        try {
            setConfigMethod =
                    JschConfigSessionFactory.class.getDeclaredMethod("setConfig", OpenSshConfig.class);
            setConfigMethod.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
        }
        SET_CONFIG_METHOD = setConfigMethod;
    }

    Git5WithAuth(GitMirror mirror, File repoDir) throws IOException {
        super(mirror, repoDir);
    }

    @Override
    FetchCommand fetch(int depth) {
        return fetch();
    }

    @Override
    public <T extends TransportCommand<?, ?>> void configureSsh(T cmd, PublicKeyMirrorCredential cred) {
        cmd.setTransportConfigCallback(transport -> {
            final SshTransport sshTransport = (SshTransport) transport;
            final JschConfigSessionFactory sessionFactory = new JschConfigSessionFactory() {
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
            };
            setEmptyConfig(sessionFactory);
            sshTransport.setSshSessionFactory(sessionFactory);
        });
    }

    @Override
    public <T extends TransportCommand<?, ?>> void configureSsh(T cmd, PasswordMirrorCredential cred) {
        cmd.setTransportConfigCallback(transport -> {
            final SshTransport sshTransport = (SshTransport) transport;
            final JschConfigSessionFactory sessionFactory = new JschConfigSessionFactory() {
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
            };
            setEmptyConfig(sessionFactory);
            sshTransport.setSshSessionFactory(sessionFactory);
        });
    }

    /**
     * Disables the default SSH config file lookup.
     */
    private static void setEmptyConfig(JschConfigSessionFactory sessionFactory) {
        try {
            if (SET_CONFIG_METHOD != null) {
                SET_CONFIG_METHOD.invoke(sessionFactory, EMPTY_CONFIG);
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns an empty {@link OpenSshConfig}.
     *
     * <p>The default {@link OpenSshConfig} reads the SSH config in `~/.ssh/config` and converts the identity
     * files into
     * {@code com.jcraft.jsch.KeyPair}. Since JSch does not support Ed25519, `KeyPair.load()` raise an
     * exception if
     * Ed25519 is used locally. Plus, Central Dogma uses {@link PublicKeyMirrorCredential}, we need to
     * provide an empty
     * config for isolated environment.
     */
    private static OpenSshConfig emptySshConfig() {
        final File emptyConfigFile;
        try {
            emptyConfigFile = File.createTempFile("dogma", "empty-ssh-config");
            emptyConfigFile.deleteOnExit();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        try {
            final Constructor<OpenSshConfig> ctor =
                    OpenSshConfig.class.getDeclaredConstructor(File.class, File.class);
            ctor.setAccessible(true);
            return ctor.newInstance(emptyConfigFile.getParentFile(), emptyConfigFile);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
