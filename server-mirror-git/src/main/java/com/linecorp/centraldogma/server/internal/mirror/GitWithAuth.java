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

import static com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredential.publicKeyPreview;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTP;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTPS;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_SSH;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.loader.KeyPairResourceParser;
import org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser;
import org.apache.sshd.common.config.keys.loader.pem.PKCS8PEMResourceKeyPairParser;
import org.apache.sshd.common.file.nonefs.NoneFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.git.transport.GitSshdSession;
import org.apache.sshd.git.transport.GitSshdSessionFactory;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.GarbageCollectCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.lib.EmptyProgressMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RemoteSession;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig.Host;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.internal.IsolatedSystemReader;
import com.linecorp.centraldogma.server.internal.JGitUtil;
import com.linecorp.centraldogma.server.internal.mirror.credential.AccessTokenMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;

final class GitWithAuth extends Git {

    private static final Logger logger = LoggerFactory.getLogger(GitWithAuth.class);

    private static final OpenSshConfig EMPTY_CONFIG = emptySshConfig();

    private static final KeyPairResourceParser keyPairResourceParser = KeyPairResourceParser.aggregate(
            // Use BouncyCastle resource parser to support non-standard formats as well.
            SecurityUtils.getBouncycastleKeyPairResourceParser(),
            PKCS8PEMResourceKeyPairParser.INSTANCE,
            OpenSSHKeyPairResourceParser.INSTANCE);

    /**
     * One of the Locks in this array is locked while a Git repository is accessed so that other GitMirrors
     * that access the same repository cannot access it at the same time. The lock is chosen based on the
     * hash code of the Git repository path. See {@link #getLock(File)} for more information.
     *
     * <p>The number of available locks is hard-coded, but it should be large enough for most use cases.
     */
    private static final Lock[] locks = new Lock[1024];

    static {
        IsolatedSystemReader.install();

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

            JGitUtil.applyDefaultsAndSave(repo.getConfig());
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

    @Override
    public LsRemoteCommand lsRemote() {
        return configure(super.lsRemote());
    }

    private <T extends TransportCommand<?, ?>> T configure(T command) {
        final MirrorCredential c = mirror.credential();
        switch (mirror.remoteRepoUri().getScheme()) {
            case SCHEME_GIT_HTTP:
            case SCHEME_GIT_HTTPS:
                if (c instanceof PasswordMirrorCredential) {
                    configureHttp(command, (PasswordMirrorCredential) c);
                } else if (c instanceof AccessTokenMirrorCredential) {
                    configureHttp(command, (AccessTokenMirrorCredential) c);
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

    private static <T extends TransportCommand<?, ?>> void configureSsh(T cmd, PublicKeyMirrorCredential cred) {
        cmd.setTransportConfigCallback(transport -> {
            final Collection<KeyPair> keyPairs;
            try {
                keyPairs = keyPairResourceParser.loadKeyPairs(null, NamedResource.ofName(cred.username()),
                                                              passwordProvider(cred), cred.privateKey());
            } catch (IOException | GeneralSecurityException e) {
                throw new MirrorException("Unexpected exception while loading private key. username: " +
                                          cred.username() + ", publicKey: " +
                                          publicKeyPreview(cred.publicKey()), e);
            }

            final GitSshdSessionFactory factory = new GitSshdSessionFactory() {
                @Override
                public RemoteSession getSession(URIish uri, CredentialsProvider credentialsProvider,
                                                FS fs, int tms) throws TransportException {
                    try {
                        return new GitSshdSession(uri, NoopCredentialsProvider.INSTANCE, fs, tms) {
                            @Override
                            protected SshClient createClient() {
                                // Not an Armeria but an SSHD client.
                                final ClientBuilder builder = ClientBuilder.builder();
                                // Do not use local file system.
                                builder.hostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
                                builder.fileSystemFactory(NoneFileSystemFactory.INSTANCE);
                                // Do not verify the server key.
                                builder.serverKeyVerifier((clientSession, remoteAddress, serverKey) -> true);
                                final SshClient client = builder.build();
                                client.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(keyPairs));
                                return client;
                            }

                            @Override
                            protected ClientSession createClientSession(
                                    SshClient clientInstance, String host, String username, int port,
                                    String... passwords) throws IOException, InterruptedException {
                                if (port <= 0) {
                                    port = 22; // Use the SSH default port it unspecified.
                                }
                                return super.createClientSession(clientInstance, host, username,
                                                                 port, passwords);
                            }
                        };
                    } catch (Exception e) {
                        throw new TransportException("Unable to connect to: " + uri +
                                                     " CredentialsProvider: " + credentialsProvider, e);
                    }
                }
            };
            final SshTransport sshTransport = (SshTransport) transport;
            sshTransport.setSshSessionFactory(factory);
        });
    }

    // TODO remove jsch dependency completely.
    private static <T extends TransportCommand<?, ?>> void configureSsh(T cmd, PasswordMirrorCredential cred) {
        cmd.setTransportConfigCallback(transport -> {
            final SshTransport sshTransport = (SshTransport) transport;
            final JschConfigSessionFactory sessionFactory = new JschConfigSessionFactory() {
                @Override
                protected void configure(Host host, Session session) {
                    try {
                        session.setHostKeyRepository(new MirrorHostKeyRepository());
                        session.setPassword(cred.password());
                    } catch (MirrorException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new MirrorException(e);
                    }
                }
            };

            // Disable the default SSH config file lookup.
            sessionFactory.setConfig(EMPTY_CONFIG);
            sshTransport.setSshSessionFactory(sessionFactory);
        });
    }

    private static FilePasswordProvider passwordProvider(PublicKeyMirrorCredential cred) {
        final String passphrase = cred.passphrase();
        if (passphrase == null) {
            return FilePasswordProvider.EMPTY;
        }

        return FilePasswordProvider.of(passphrase);
    }

    private static <T extends TransportCommand<?, ?>> void configureHttp(T cmd, PasswordMirrorCredential cred) {
        cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(cred.username(), cred.password()));
    }

    private static <T extends TransportCommand<?, ?>> void configureHttp(
            T cmd, AccessTokenMirrorCredential cred) {
        cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", cred.accessToken()));
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

    protected static final class MirrorHostKeyRepository implements HostKeyRepository {

        private static final HostKey[] EMPTY_HOST_KEYS = new HostKey[0];

        MirrorHostKeyRepository() {
            // TODO(trustin): Store the hostkeys in the meta repository.
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

    private static final class NoopCredentialsProvider extends CredentialsProvider {

        static final CredentialsProvider INSTANCE = new NoopCredentialsProvider();

        @Override
        public boolean isInteractive() {
            return true; // Hacky way in order not to use username and password.
        }

        @Override
        public boolean supports(CredentialItem... items) {
            return false;
        }

        @Override
        public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
            return false;
        }
    }

    /**
     * Returns an empty {@link OpenSshConfig}.
     *
     * <p>The default {@link OpenSshConfig} reads the SSH config in `~/.ssh/config` and converts the identity
     * files into {@code com.jcraft.jsch.KeyPair}. Since JSch does not support Ed25519, `KeyPair.load()`
     * raise an exception if Ed25519 is used locally. Plus, Central Dogma uses
     * {@link PublicKeyMirrorCredential}, we need to provide an empty config for an isolated environment.
     */
    private static OpenSshConfig emptySshConfig() {
        final File emptyConfigFile;
        try {
            emptyConfigFile = File.createTempFile("dogma", "empty-ssh-config");
            emptyConfigFile.deleteOnExit();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        return new OpenSshConfig(emptyConfigFile.getParentFile(), emptyConfigFile);
    }
}
