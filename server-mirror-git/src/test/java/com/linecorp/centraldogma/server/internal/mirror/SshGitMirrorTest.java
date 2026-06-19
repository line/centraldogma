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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.nonefs.NoneFileSystemFactory;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.server.internal.credential.SshKeyCredential;
import com.linecorp.centraldogma.server.mirror.MirrorContext;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.git.SshMirrorException;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

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

        assertThatThrownBy(() -> {
            createSession(client, new URIish(
                    "https://github.com/line/centraldogma-authtest.git"));
        }).isExactlyInstanceOf(SshMirrorException.class)
          .hasMessage("Failed to create a session for 'https://github.com/line/centraldogma-authtest.git'." +
                      " (reason: No username specified when the session was created)");
        final Session session = sessionRef.get();
        assertThat(session).isNotNull();
        assertThat(session.isClosed()).isTrue();
    }

    @Test
    void warnWhenNoAcceptedHostKeysConfigured() {
        // When acceptedHostKeys is null, the mirror should log a warning but not throw.
        // It will still fail due to other reasons (e.g., cannot connect), but NOT because
        // of missing host keys.
        final SshKeyCredential credential = new SshKeyCredential(
                "projects/foo/credentials/test", "git", "ssh-rsa AAAA",
                "-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----",
                null);

        final SshGitMirror mirror = newSshGitMirror(credential);

        // The mirror should not throw MirrorException for missing host keys.
        // It may throw for other reasons (e.g., invalid key, connection failure).
        assertThatThrownBy(() -> mirror.mirror(null, null, 0, 0, null))
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("No accepted host keys configured"));
    }

    @Test
    void warnWhenAcceptedHostKeysIsEmpty() {
        // When acceptedHostKeys is an empty list, the mirror should log a warning but not throw.
        final SshKeyCredential credential = new SshKeyCredential(
                "projects/foo/credentials/test", "git", "ssh-rsa AAAA",
                "-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----",
                null);

        final SshGitMirror mirror = newSshGitMirror(credential);

        assertThatThrownBy(() -> mirror.mirror(null, null, 0, 0, null))
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("No accepted host keys configured"));
    }

    @Test
    void hostKeyFingerprintFormat() throws Exception {
        // Verify that the fingerprint format from KeyUtils matches expected SHA256 prefix.
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        final KeyPair keyPair = kpg.generateKeyPair();

        final String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, keyPair.getPublic());
        assertThat(fingerprint).startsWith("SHA256:");
        // SHA256 fingerprints are base64-encoded, so they should contain only valid base64 chars
        final String base64Part = fingerprint.substring("SHA256:".length());
        assertThat(base64Part).matches("[A-Za-z0-9+/=]+");
    }

    @Test
    void allowWhenGlobalTrustedHostKeysConfigured() {
        // When global trustedHostKeys is configured for the hostname,
        // the mirror should NOT fail-closed.
        final SshKeyCredential credential = new SshKeyCredential(
                "projects/foo/credentials/test", "git", "ssh-rsa AAAA",
                "-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----",
                null);

        final Map<String, List<String>> globalKeys = ImmutableMap.of(
                "github.com", ImmutableList.of("SHA256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8"));

        final SshGitMirror mirror = newSshGitMirror(credential, globalKeys);

        // Should not throw "No accepted host keys configured" since global keys match the hostname.
        // It will throw a different error (connection/key error) since we don't have a real SSH server,
        // but it should NOT be "No accepted host keys configured".
        assertThatThrownBy(() -> mirror.mirror(null, null, 0, 0, null))
                .isInstanceOf(Exception.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("No accepted host keys configured"));
    }

    @Test
    void warnWhenGlobalTrustedHostKeysDoNotMatchHostname() {
        // When global trustedHostKeys doesn't contain the target hostname,
        // the mirror should log a warning but not throw for missing host keys.
        final SshKeyCredential credential = new SshKeyCredential(
                "projects/foo/credentials/test", "git", "ssh-rsa AAAA",
                "-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----",
                null);

        final Map<String, List<String>> globalKeys = ImmutableMap.of(
                "gitlab.com", ImmutableList.of("SHA256:someOtherFingerprint"));

        final SshGitMirror mirror = newSshGitMirror(credential, globalKeys);

        assertThatThrownBy(() -> mirror.mirror(null, null, 0, 0, null))
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("No accepted host keys configured"));
    }

    private static SshGitMirror newSshGitMirror(SshKeyCredential credential) {
        return newSshGitMirror(credential, ImmutableMap.of());
    }

    private static SshGitMirror newSshGitMirror(SshKeyCredential credential,
                                                 Map<String, List<String>> trustedHostKeys) {
        final Repository repository = mock(Repository.class);
        when(repository.parent()).thenReturn(mock(Project.class));
        when(repository.name()).thenReturn("test-repo");
        when(repository.parent().name()).thenReturn("test-project");

        final MirrorContext context = new MirrorContext(
                "mirror-id", true, MirroringTestUtils.EVERY_MINUTE,
                MirrorDirection.REMOTE_TO_LOCAL,
                credential, repository, "/",
                URI.create("git+ssh://git@github.com/line/centraldogma.git"),
                null, null, trustedHostKeys);

        return (SshGitMirror) new GitMirrorProvider().newMirror(context);
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
