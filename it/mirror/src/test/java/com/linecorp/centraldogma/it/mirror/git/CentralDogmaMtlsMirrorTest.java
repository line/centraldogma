/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.it.mirror.git;

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialFile;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientTlsConfig;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.SignedCertificateExtension;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.TlsConfig;
import com.linecorp.centraldogma.server.auth.MtlsConfig;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.TestUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class CentralDogmaMtlsMirrorTest {

    private static final int MAX_NUM_FILES = 32;
    private static final long MAX_NUM_BYTES = 1048576; // 1 MiB

    private static final String REPO_FOO = "foo";
    private static final String TRUST_STORE_PASSWORD = "changeit";

    private static final String CERT_COMMON_NAME = "mirror-client";

    // CA and client certificates for mTLS.
    @Order(1)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension();

    @Order(2)
    @RegisterExtension
    static final SelfSignedCertificateExtension ca = new SelfSignedCertificateExtension();

    @Order(3)
    @RegisterExtension
    static final SignedCertificateExtension clientCert =
            new SignedCertificateExtension(CERT_COMMON_NAME, ca, false);

    // The "local" server that has the mirroring service enabled (plain HTTP).
    @RegisterExtension
    static final CentralDogmaExtension localDogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.pluginConfigs(
                    new MirroringServicePluginConfig(true, 1, MAX_NUM_FILES, MAX_NUM_BYTES, false));
        }
    };

    private static final String USERNAME = TestAuthMessageUtil.USERNAME;
    private static final String PASSWORD = TestAuthMessageUtil.PASSWORD;

    // The "remote" server with HTTPS + mTLS + auth enabled.
    @RegisterExtension
    static final CentralDogmaExtension remoteDogma = new CentralDogmaExtension(true) {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
            builder.port(0, SessionProtocol.HTTPS);
            builder.tls(new TlsConfig(serverCert.certificateFile(), serverCert.privateKeyFile(),
                                      null, null, null));
            builder.mtlsConfig(new MtlsConfig(true, ImmutableList.of(ca.certificateFile())));
        }

        @Override
        protected String accessToken() {
            final TlsKeyPair tlsKeyPair = TlsKeyPair.of(clientCert.privateKey(),
                                                        clientCert.certificate());
            final ClientTlsConfig tlsConfig =
                    ClientTlsConfig.builder()
                                   .tlsCustomizer(b -> b.trustManager(serverCert.certificate()))
                                   .build();
            try (ClientFactory factory = ClientFactory.builder()
                                                       .tlsProvider(TlsProvider.of(tlsKeyPair), tlsConfig)
                                                       .build()) {
                final WebClient client = WebClient.builder("https://127.0.0.1:" +
                                                           remoteDogma.serverAddress().getPort())
                                                  .factory(factory)
                                                  .build();
                return TestAuthMessageUtil.getAccessToken(client, USERNAME, PASSWORD,
                                                          "mirrorApp", true, true, false);
            }
        }

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            // Configure the test's own HTTP client to trust the server cert and present client cert.
            final TlsKeyPair tlsKeyPair = TlsKeyPair.of(clientCert.privateKey(),
                                                        clientCert.certificate());
            remoteClientFactory =
                    ClientFactory.builder()
                                 .tlsProvider(TlsProvider.of(tlsKeyPair),
                                              ClientTlsConfig.builder()
                                                             .tlsCustomizer(b -> b.trustManager(
                                                                     serverCert.certificate()))
                                                             .build())
                                 .build();
            builder.factory(remoteClientFactory);
        }
    };

    private static CentralDogma localClient;
    private static CentralDogma remoteClient;
    private static MirroringService mirroringService;
    private static ClientFactory remoteClientFactory;

    private static String oldTrustStore;
    private static String oldTrustStorePassword;
    private static Path trustStorePath;

    @BeforeAll
    static void init() throws Exception {
        localClient = localDogma.client();
        remoteClient = remoteDogma.client();
        mirroringService = localDogma.mirroringService();

        // Register the client certificate's CN as an app identity on the remote server
        // so that mTLS-authenticated requests are authorized.
        final AggregatedHttpResponse response =
                remoteDogma.httpClient()
                           .post("/api/v1/appIdentities",
                                 QueryParams.of("appId", "mirror-app",
                                                "type", "CERTIFICATE",
                                                "certificateId", CERT_COMMON_NAME,
                                                "isSystemAdmin", true),
                                 HttpData.empty())
                           .aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        // Create a temporary trust store containing the server's certificate
        // so that the mirror's CentralDogma client trusts the remote server.
        trustStorePath = Files.createTempFile("truststore", ".jks");
        final KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, TRUST_STORE_PASSWORD.toCharArray());
        trustStore.setCertificateEntry("server", serverCert.certificate());
        try (OutputStream os = new FileOutputStream(trustStorePath.toFile())) {
            trustStore.store(os, TRUST_STORE_PASSWORD.toCharArray());
        }

        // Save old system properties and set new ones.
        oldTrustStore = System.getProperty("javax.net.ssl.trustStore");
        oldTrustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
        System.setProperty("javax.net.ssl.trustStore", trustStorePath.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", TRUST_STORE_PASSWORD);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (remoteClientFactory != null) {
            remoteClientFactory.close();
        }
        // Restore old system properties.
        if (oldTrustStore != null) {
            System.setProperty("javax.net.ssl.trustStore", oldTrustStore);
        } else {
            System.clearProperty("javax.net.ssl.trustStore");
        }
        if (oldTrustStorePassword != null) {
            System.setProperty("javax.net.ssl.trustStorePassword", oldTrustStorePassword);
        } else {
            System.clearProperty("javax.net.ssl.trustStorePassword");
        }
        Files.deleteIfExists(trustStorePath);
    }

    private String projName;

    @BeforeEach
    void initRepos(TestInfo testInfo) {
        projName = TestUtil.normalizedDisplayName(testInfo);
        localClient.createProject(projName).join();
        localClient.createRepository(projName, REPO_FOO).join();
        remoteClient.createProject(projName).join();
        remoteClient.createRepository(projName, REPO_FOO).join();
    }

    @AfterEach
    void destroyRepos() {
        localClient.removeProject(projName).join();
        remoteClient.removeProject(projName).join();
    }

    @Test
    void localToRemote_withMtls() throws Exception {
        pushMirrorSettings("/", "/");

        // Add files to local and mirror.
        localClient.forRepo(projName, REPO_FOO)
                   .commit("Add files",
                           Change.ofJsonUpsert("/foo.json", "{\"mtls\":true}"),
                           Change.ofTextUpsert("/bar.txt", "secured"))
                   .push().join();

        mirroringService.mirror().join();

        // Verify the files are mirrored to the remote via mTLS.
        final Map<String, Entry<?>> remoteEntries =
                remoteClient.getFiles(projName, REPO_FOO, Revision.HEAD, PathPattern.all()).join();
        assertThat(remoteEntries).containsKey("/foo.json");
        assertThat(remoteEntries).containsKey("/bar.txt");
        assertThat(remoteEntries.get("/foo.json").contentAsText()).contains("\"mtls\"");

        // Mirror again with no changes - should be a no-op.
        final Revision remoteRevBefore = remoteClient.normalizeRevision(
                projName, REPO_FOO, Revision.HEAD).join();
        mirroringService.mirror().join();
        final Revision remoteRevAfter = remoteClient.normalizeRevision(
                projName, REPO_FOO, Revision.HEAD).join();
        assertThat(remoteRevAfter).isEqualTo(remoteRevBefore);
    }

    private void pushMirrorSettings(String localPath, String remotePath) {
        final InetSocketAddress remoteAddr = remoteDogma.serverAddress();
        final String remoteUri = "dogma+https://127.0.0.1:" + remoteAddr.getPort() +
                                 '/' + projName + '/' + REPO_FOO + ".dogma" + remotePath;

        // Use SshKeyCredential with the client certificate and private key for mTLS.
        final String credId = "mtls-cert";
        final String credName = credentialName(projName, credId);
        final String certPem = pemEncode(clientCert.certificate());
        final String keyPem = pemEncode(clientCert.privateKey());
        localClient.forRepo(projName, Project.REPO_DOGMA)
                   .commit("Add mTLS credential",
                           Change.ofJsonUpsert(credentialFile(credName),
                                               "{ \"type\": \"SSH_KEY\"," +
                                               "  \"name\": \"" + credName + "\"," +
                                               "  \"username\": \"mirror-client\"," +
                                               "  \"publicKey\": \"" + escapeJson(certPem) + "\"," +
                                               "  \"privateKey\": \"" + escapeJson(keyPem) + "\" }"))
                   .push().join();

        localClient.forRepo(projName, Project.REPO_DOGMA)
                   .commit("Add mirror config",
                           Change.ofJsonUpsert(
                                   "/repos/" + REPO_FOO + "/mirrors/foo.json",
                                   '{' +
                                   "  \"id\": \"foo\"," +
                                   "  \"enabled\": true," +
                                   "  \"direction\": \"" + MirrorDirection.LOCAL_TO_REMOTE + "\"," +
                                   "  \"localRepo\": \"" + REPO_FOO + "\"," +
                                   "  \"localPath\": \"" + localPath + "\"," +
                                   "  \"remoteUri\": \"" + remoteUri + "\"," +
                                   "  \"schedule\": \"0 0 0 1 1 ? 2099\"," +
                                   "  \"credentialName\": \"" + credName + '"' +
                                   '}'))
                   .push().join();
    }

    private static String pemEncode(X509Certificate cert) {
        try {
            final Encoder encoder = Base64.getMimeEncoder(64, "\n".getBytes());
            final String encoded = encoder.encodeToString(cert.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + encoded + "\n-----END CERTIFICATE-----";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String pemEncode(PrivateKey key) {
        final Encoder encoder = Base64.getMimeEncoder(64, "\n".getBytes());
        final String encoded = encoder.encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
