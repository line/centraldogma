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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Collection;

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
import org.apache.sshd.git.GitModuleProperties;
import org.apache.sshd.git.transport.GitSshdSessionFactory;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.Cron;

import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class SshGitMirror extends AbstractGitMirror {

    private static final Logger logger = LoggerFactory.getLogger(SshGitMirror.class);

    private static final KeyPairResourceParser keyPairResourceParser = KeyPairResourceParser.aggregate(
            // Use BouncyCastle resource parser to support non-standard formats as well.
            SecurityUtils.getBouncycastleKeyPairResourceParser(),
            PKCS8PEMResourceKeyPairParser.INSTANCE,
            OpenSSHKeyPairResourceParser.INSTANCE);

    // Creates BouncyCastleRandom once and reuses it.
    // Otherwise, BouncyCastleRandom is created whenever the SSH client is created that leads to
    // blocking the thread to get enough entropy for SecureRandom.
    // We might create multiple BouncyCastleRandom later and poll them, if necessary.
    private static final BouncyCastleRandom bounceCastleRandom = new BouncyCastleRandom();

    SshGitMirror(Cron schedule, MirrorDirection direction, MirrorCredential credential,
                 Repository localRepo, String localPath,
                 URI remoteRepoUri, String remotePath, @Nullable String remoteBranch,
                 @Nullable String gitignore) {
        super(schedule, direction, credential, localRepo, localPath, remoteRepoUri, remotePath, remoteBranch,
              gitignore);
    }

    @Override
    protected void mirrorLocalToRemote(File workDir, int maxNumFiles, long maxNumBytes) throws Exception {
        final SshClient sshClient = createSshClient();
        final URIish remoteUri = remoteUri();
        final ClientSession session = createSession(sshClient, remoteUri);
        final DefaultGitSshdSessionFactory sessionFactory =
                new DefaultGitSshdSessionFactory(sshClient, session);
        try (GitWithAuth git = openGit(workDir, remoteUri, sessionFactory::configureCommand)) {
            mirrorLocalToRemote(git, maxNumFiles, maxNumBytes);
        } finally {
            try {
                session.close(true);
            } finally {
                sshClient.stop();
            }
        }
    }

    @Override
    protected void mirrorRemoteToLocal(File workDir, CommandExecutor executor,
                                       int maxNumFiles, long maxNumBytes) throws Exception {
        final SshClient sshClient = createSshClient();
        final URIish remoteUri = remoteUri();
        final ClientSession session = createSession(sshClient, remoteUri);
        final DefaultGitSshdSessionFactory sessionFactory =
                new DefaultGitSshdSessionFactory(sshClient, session);
        try (GitWithAuth git = openGit(workDir, remoteUri, sessionFactory::configureCommand)) {
            mirrorRemoteToLocal(git, executor, maxNumFiles, maxNumBytes);
        } finally {
            try {
                session.close(true);
            } finally {
                sshClient.stop();
            }
        }
    }

    private URIish remoteUri() throws URISyntaxException {
        // Requires the username to be included in the URI.
        final String username;
        if (credential() instanceof PasswordMirrorCredential) {
            username = ((PasswordMirrorCredential) credential()).username();
        } else if (credential() instanceof PublicKeyMirrorCredential) {
            username = ((PublicKeyMirrorCredential) credential()).username();
        } else {
            username = null;
        }

        assert !remoteRepoUri().getRawAuthority().contains("@") : remoteRepoUri().getRawAuthority();
        final String jGitUri;
        if (username != null) {
            jGitUri = "ssh://" + username + '@' + remoteRepoUri().getRawAuthority() +
                      remoteRepoUri().getRawPath();
        } else {
            jGitUri = "ssh://" + remoteRepoUri().getRawAuthority() + remoteRepoUri().getRawPath();
        }
        return new URIish(jGitUri);
    }

    private SshClient createSshClient() {
        final ClientBuilder builder = ClientBuilder.builder();
        // Do not use local file system.
        builder.hostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        builder.fileSystemFactory(NoneFileSystemFactory.INSTANCE);
        // Do not verify the server key.
        builder.serverKeyVerifier((clientSession, remoteAddress, serverKey) -> true);
        builder.randomFactory(() -> bounceCastleRandom);
        final SshClient client = builder.build();
        configureCredential(client);
        client.start();
        return client;
    }

    private static ClientSession createSession(SshClient sshClient, URIish uri) {
        try {
            int port = uri.getPort();
            if (port <= 0) {
                port = 22; // Use the SSH default port it unspecified.
            }
            logger.trace("Connecting to {}:{}", uri.getHost(), port);
            final ClientSession session = sshClient.connect(uri.getUser(), uri.getHost(), port)
                                                   .verify(GitModuleProperties.CONNECT_TIMEOUT.getRequired(
                                                           sshClient))
                                                   .getSession();
            session.auth().verify(GitModuleProperties.AUTH_TIMEOUT.getRequired(session));
            logger.trace("The session established: {}", session);
            return session;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void configureCredential(SshClient client) {
        final MirrorCredential c = credential();
        if (c instanceof PasswordMirrorCredential) {
            client.setFilePasswordProvider(passwordProvider(((PasswordMirrorCredential) c).password()));
        } else if (c instanceof PublicKeyMirrorCredential) {
            final PublicKeyMirrorCredential cred = (PublicKeyMirrorCredential) credential();
            final Collection<KeyPair> keyPairs;
            try {
                keyPairs = keyPairResourceParser.loadKeyPairs(null, NamedResource.ofName(cred.username()),
                                                              passwordProvider(cred.passphrase()),
                                                              cred.privateKey());
                client.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(keyPairs));
            } catch (IOException | GeneralSecurityException e) {
                throw new MirrorException("Unexpected exception while loading private key. username: " +
                                          cred.username() + ", publicKey: " +
                                          publicKeyPreview(cred.publicKey()), e);
            }
        }
    }

    private static FilePasswordProvider passwordProvider(@Nullable String passphrase) {
        if (passphrase == null) {
            return FilePasswordProvider.EMPTY;
        }

        return FilePasswordProvider.of(passphrase);
    }

    private static final class DefaultGitSshdSessionFactory extends GitSshdSessionFactory {
        DefaultGitSshdSessionFactory(SshClient client, ClientSession session) {
            // The constructor is protected, so we should inherit the class.
            super(client, session);
        }

        private void configureCommand(TransportCommand<?, ?> command) {
            // Need to set the credentials provider, otherwise NPE is raised when creating GitSshdSession.
            command.setCredentialsProvider(NoopCredentialsProvider.INSTANCE);
            command.setTransportConfigCallback(transport -> {
                final SshTransport sshTransport = (SshTransport) transport;
                sshTransport.setSshSessionFactory(this);
            });
        }
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
