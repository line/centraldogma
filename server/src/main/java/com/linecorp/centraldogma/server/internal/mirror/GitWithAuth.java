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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTP;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTPS;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_SSH;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.GarbageCollectCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.lib.EmptyProgressMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig.Host;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jcraft.jsch.Buffer;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.Identity;
import com.jcraft.jsch.IdentityRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;

final class GitWithAuth extends Git {

    private static final Logger logger = LoggerFactory.getLogger(GitWithAuth.class);

    /**
     * One of the Locks in this array is locked while a Git repository is accessed so that other GitMirrors
     * that access the same repository cannot access it at the same time. The lock is chosen based on the
     * hash code of the Git repository path. See {@link #getLock(File)} for more information.
     *
     * <p>The number of available locks is hard-coded, but it should be large enough for most use cases.
     */
    private static final Lock[] locks = new Lock[1024];

    static {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    private static Lock getLock(File repoDir) {
        final int h = repoDir.getPath().hashCode();
        return locks[Math.abs((h ^ h >>> 16) % locks.length)];
    }

    private final GitMirror mirror;
    private final Lock lock;
    private final Map<String, ProgressMonitor> progressMonitors = new HashMap<>();

    GitWithAuth(GitMirror mirror, File repoDir) throws IOException {
        super(repo(repoDir));
        this.mirror = mirror;
        lock = getLock(repoDir);
    }

    private static Repository repo(File repoDir) throws IOException {
        final Lock lock = getLock(repoDir);
        boolean success = false;
        lock.lock();
        try {
            repoDir.getParentFile().mkdirs();
            final Repository repo = new RepositoryBuilder().setGitDir(repoDir).setBare().build();
            if (!repo.getObjectDatabase().exists()) {
                repo.create(true);
            }
            success = true;
            return repo;
        } finally {
            if (!success) {
                lock.unlock();
            }
        }
    }

    @Override
    public void close() {
        try {
            super.close();
        } finally {
            try {
                getRepository().close();
            } finally {
                lock.unlock();
            }
        }
    }

    private ProgressMonitor progressMonitor(String name) {
        return progressMonitors.computeIfAbsent(name, MirrorProgressMonitor::new);
    }

    @Override
    public FetchCommand fetch() {
        return configure(super.fetch()).setProgressMonitor(progressMonitor("fetch"));
    }

    @Override
    public PushCommand push() {
        return configure(super.push()).setProgressMonitor(progressMonitor("push"));
    }

    @Override
    public GarbageCollectCommand gc() {
        return super.gc().setProgressMonitor(progressMonitor("gc"));
    }

    private <T extends TransportCommand<?, ?>> T configure(T command) {
        final MirrorCredential c = mirror.credential();
        switch (mirror.remoteRepoUri().getScheme()) {
            case SCHEME_GIT_HTTP:
            case SCHEME_GIT_HTTPS:
                if (c instanceof PasswordMirrorCredential) {
                    configureHttp(command, (PasswordMirrorCredential) c);
                }
                break;
            case SCHEME_GIT_SSH:
                if (c instanceof PasswordMirrorCredential) {
                    configureSsh(command, (PasswordMirrorCredential) c);
                } else if (c instanceof PublicKeyMirrorCredential) {
                    configureSsh(command, (PublicKeyMirrorCredential) c);
                }
                break;
        }

        return command;
    }

    private static <T extends TransportCommand<?, ?>> void configureHttp(T cmd, PasswordMirrorCredential cred) {
        cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(cred.username(), cred.password()));
    }

    private <T extends TransportCommand<?, ?>> void configureSsh(T cmd, PublicKeyMirrorCredential cred) {
        cmd.setTransportConfigCallback(transport -> {
            final SshTransport sshTransport = (SshTransport) transport;
            sshTransport.setSshSessionFactory(new JschConfigSessionFactory() {
                @Override
                protected void configure(Host host, Session session) {
                    try {
                        session.setHostKeyRepository(
                                new MirrorHostKeyRepository(mirror.localRepo().parent().metaRepo()));
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

    private <T extends TransportCommand<?, ?>> void configureSsh(T cmd, PasswordMirrorCredential cred) {
        cmd.setTransportConfigCallback(transport -> {
            final SshTransport sshTransport = (SshTransport) transport;
            sshTransport.setSshSessionFactory(new JschConfigSessionFactory() {
                @Override
                protected void configure(Host host, Session session) {
                    try {
                        session.setHostKeyRepository(
                                new MirrorHostKeyRepository(mirror.localRepo().parent().metaRepo()));
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

    private final class MirrorProgressMonitor extends EmptyProgressMonitor {

        private final String operationName;

        MirrorProgressMonitor(String operationName) {
            this.operationName = requireNonNull(operationName, "operationName");
        }

        @Override
        public void beginTask(String title, int totalWork) {
            if (totalWork > 0 && logger.isInfoEnabled()) {
                logger.info("[{}] {} ({}, total: {})", operationName, mirror.remoteRepoUri(), title, totalWork);
            }
        }
    }

    private static final class MirrorHostKeyRepository implements HostKeyRepository {

        private static final HostKey[] EMPTY_HOST_KEYS = new HostKey[0];

        MirrorHostKeyRepository(MetaRepository metaRepo) {
            // TODO(trustin): Store the hostkeys in the meta repository.
            // metaRepo.knownSshHosts();
        }

        @Override
        public int check(String host, byte[] key) {
            return OK;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {}

        @Override
        public void remove(String host, String type) {}

        @Override
        public void remove(String host, String type, byte[] key) {}

        @Override
        public String getKnownHostsRepositoryID() {
            return getClass().getSimpleName();
        }

        @Override
        public HostKey[] getHostKey() {
            throw new UnsupportedOperationException();
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            // TODO(trustin): Store the hostkeys in the meta repository.
            return EMPTY_HOST_KEYS;
        }
    }

    private static final class MirrorIdentityRepository implements IdentityRepository {
        private final Identity identity;

        MirrorIdentityRepository(String name, PublicKeyMirrorCredential cred) throws JSchException {
            final KeyPair keyPair = KeyPair.load(new JSch(), cred.privateKey(), cred.publicKey());
            final Buffer buf = new Buffer(keyPair.getPublicKeyBlob());
            final String algName = new String(buf.getString(), StandardCharsets.US_ASCII);
            if (!keyPair.decrypt(cred.passphrase())) {
                throw new MirrorException("cannot decrypt the private key with the given passphrase");
            }

            identity = new Identity() {
                @Override
                public String getName() {
                    return name;
                }

                @Override
                public String getAlgName() {
                    return algName;
                }

                @Override
                public byte[] getPublicKeyBlob() {
                    return keyPair.getPublicKeyBlob();
                }

                @Override
                public byte[] getSignature(byte[] data) {
                    return keyPair.getSignature(data);
                }

                @Override
                public boolean setPassphrase(byte[] passphrase) {
                    return keyPair.decrypt(passphrase);
                }

                @Override
                public boolean isEncrypted() {
                    return keyPair.isEncrypted();
                }

                @Override
                @Deprecated
                public boolean decrypt() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void clear() {
                    keyPair.dispose();
                }
            };
        }

        @Override
        public String getName() {
            return getClass().getSimpleName();
        }

        @Override
        public int getStatus() {
            return RUNNING;
        }

        @Override
        public Vector<Identity> getIdentities() {
            final Vector<Identity> identities = new Vector<>();
            identities.add(identity);
            return identities;
        }

        @Override
        public boolean add(byte[] identity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(byte[] blob) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeAll() {
            throw new UnsupportedOperationException();
        }
    }
}
