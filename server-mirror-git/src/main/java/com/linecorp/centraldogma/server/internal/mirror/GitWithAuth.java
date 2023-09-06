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

import javax.annotation.Nullable;

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
import org.apache.sshd.common.util.security.bouncycastle.BouncyCastleRandom;
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
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.internal.IsolatedSystemReader;
import com.linecorp.centraldogma.server.internal.JGitUtil;
import com.linecorp.centraldogma.server.internal.mirror.credential.AccessTokenMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;

final class GitWithAuth extends Git {

    private static final Logger logger = LoggerFactory.getLogger(GitWithAuth.class);

    static final KeyPairResourceParser keyPairResourceParser = KeyPairResourceParser.aggregate(
            // Use BouncyCastle resource parser to support non-standard formats as well.
            SecurityUtils.getBouncycastleKeyPairResourceParser(),
            PKCS8PEMResourceKeyPairParser.INSTANCE,
            OpenSSHKeyPairResourceParser.INSTANCE);

    // Creates BouncyCastleRandom once and reuses it.
    // Otherwise, BouncyCastleRandom is created whenever the SSH client is created that leads to
    // blocking the thread to get enough entropy for SecureRandom.
    // We might create multiple BouncyCastleRandom later and poll them, if necessary.
    static final BouncyCastleRandom bounceCastleRandom = new BouncyCastleRandom();

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

    private final AbstractGitMirror mirror;
    private final Lock lock;
    private final URIish remoteUri;
    private final Map<String, ProgressMonitor> progressMonitors = new HashMap<>();

    GitWithAuth(AbstractGitMirror mirror, File repoDir, URIish remoteUri) throws IOException {
        super(repo(repoDir));
        this.mirror = mirror;
        lock = getLock(repoDir);
        this.remoteUri = remoteUri;
    }

    URIish remoteUri() {
        return remoteUri;
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
                command.setCredentialsProvider(NoopCredentialsProvider.INSTANCE);
                break;
        }

        return command;
    }

    private static <T extends TransportCommand<?, ?>> void configureSsh(T cmd, PublicKeyMirrorCredential cred) {
        final Collection<KeyPair> keyPairs;
        try {
            keyPairs = keyPairResourceParser.loadKeyPairs(null, NamedResource.ofName(cred.username()),
                                                          passwordProvider(cred.passphrase()),
                                                          cred.privateKey());
        } catch (IOException | GeneralSecurityException e) {
            throw new MirrorException("Unexpected exception while loading private key. username: " +
                                      cred.username() + ", publicKey: " +
                                      publicKeyPreview(cred.publicKey()), e);
        }
        cmd.setTransportConfigCallback(transport -> {
            final GitSshdSessionFactory factory = new DefaultGitSshdSessionFactory() {
                @Override
                void onClientCreated(SshClient client) {
                    client.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(keyPairs));
                }
            };
            final SshTransport sshTransport = (SshTransport) transport;
            sshTransport.setSshSessionFactory(factory);
        });
    }

    private static <T extends TransportCommand<?, ?>> void configureSsh(T cmd, PasswordMirrorCredential cred) {
        cmd.setTransportConfigCallback(transport -> {
            final GitSshdSessionFactory factory = new DefaultGitSshdSessionFactory() {
                @Override
                void onClientCreated(SshClient client) {
                    client.setFilePasswordProvider(passwordProvider(cred.password()));
                }
            };
            final SshTransport sshTransport = (SshTransport) transport;
            sshTransport.setSshSessionFactory(factory);
        });
    }

    static FilePasswordProvider passwordProvider(@Nullable String passphrase) {
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

    private static class DefaultGitSshdSessionFactory extends GitSshdSessionFactory {
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
                        builder.randomFactory(() -> bounceCastleRandom);
                        final SshClient client = builder.build();
                        onClientCreated(client);
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

        void onClientCreated(SshClient client) {}
    }

    static final class NoopCredentialsProvider extends CredentialsProvider {

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
}
