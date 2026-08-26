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

package com.linecorp.centraldogma.it.mirror.git;

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.io.Resources;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.credential.CreateCredentialRequest;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class GitMirrorSshAuthTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.pluginConfigs(new MirroringServicePluginConfig(true));
        }
    };

    // The SSH test key files required for GitMirrorSshAuthTest are stored as GitHub Secrets and
    // automatically created during CI builds.

    // Test Git-over-SSH with the read-only GitHub deploy key of the test repository.
    //
    // Note to security auditors:
    //
    //   Do not report any security issues about the SSH key pair being used in this test.
    //   It is intentionally checked in to the source code repository and used only for
    //   accessing an empty read-only private repository dedicated to test if SSH authentication works.
    //   We are very sure that it has access to no other repositories.
    //

    private static CentralDogma client;
    private static MirroringService mirroringService;

    @BeforeAll
    static void setUp() {
        client = dogma.client();
        mirroringService = dogma.mirroringService();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("arguments")
    @DisabledIf("noSshKeys")
    void sshAuth(String projName, String gitUri, JsonNode credential) throws Exception {
        client.createProject(projName).join();
        client.createRepository(projName, "main").join();

        final String credentialName = credential.get("name").asText();
        final String credentialId = credentialName.substring(credentialName.lastIndexOf('/') + 1);
        final BlockingWebClient webClient = dogma.blockingHttpClient();

        // Create the credential first; a git+ssh mirror requires an existing SSH_KEY credential.
        final CreateCredentialRequest credentialRequest =
                new CreateCredentialRequest(credentialId, Jackson.treeToValue(credential, Credential.class));
        final ResponseEntity<PushResultDto> credentialResponse =
                webClient.prepare()
                         .post("/api/v1/projects/{proj}/credentials")
                         .pathParam("proj", projName)
                         .contentJson(credentialRequest)
                         .asJson(PushResultDto.class)
                         .execute();
        assertThat(credentialResponse.status()).isEqualTo(HttpStatus.CREATED);

        // git+ssh://github.com/line/centraldogma-authtest.git
        final String remoteScheme = "git+ssh";
        final String remoteUrl = gitUri.substring((remoteScheme + "://").length());
        final MirrorRequest mirror =
                new MirrorRequest("main", true, projName, "0 0 0 1 1 ? 2099", "REMOTE_TO_LOCAL", "main",
                                  "/", remoteScheme, remoteUrl, "/", "main", null, credentialName, null);
        final ResponseEntity<PushResultDto> mirrorResponse =
                webClient.prepare()
                         .post("/api/v1/projects/{proj}/repos/{repo}/mirrors")
                         .pathParam("proj", projName)
                         .pathParam("repo", "main")
                         .contentJson(mirror)
                         .asJson(PushResultDto.class)
                         .execute();
        assertThat(mirrorResponse.status()).isEqualTo(HttpStatus.CREATED);

        // Try to perform mirroring to see if authentication works as expected.
        mirroringService.mirror().join();
        client.removeProject(projName).join();
    }

    static boolean noSshKeys() {
        // Check if SSH key resources are available
        return GitMirrorSshAuthTest.class.getResource("ecdsa_256.openssh") == null;
    }

    private static Collection<Arguments> arguments() throws Exception {
        final ImmutableSet.Builder<Arguments> builder = ImmutableSet.builder();

        addSshAuth(builder, "ecdsa_256.openssh", "ecdsa_256.openssh.pub", "");
        addSshAuth(builder, "ecdsa_256.openssh.password", "ecdsa_256.openssh.pub", "secret");
        addSshAuth(builder, "ecdsa_256.pem", "ecdsa_256.openssh.pub", "");
        addSshAuth(builder, "ecdsa_256.pem.password", "ecdsa_256.openssh.pub", "secret");

        addSshAuth(builder, "ecdsa_384.openssh", "ecdsa_384.openssh.pub", "");
        addSshAuth(builder, "ecdsa_384.openssh.password", "ecdsa_384.openssh.pub", "secret");
        addSshAuth(builder, "ecdsa_384.pem", "ecdsa_384.openssh.pub", "");
        addSshAuth(builder, "ecdsa_384.pem.password", "ecdsa_384.openssh.pub", "secret");

        addSshAuth(builder, "ecdsa_521.openssh", "ecdsa_521.openssh.pub", "");
        addSshAuth(builder, "ecdsa_521.openssh.password", "ecdsa_521.openssh.pub", "secret");
        addSshAuth(builder, "ecdsa_521.pem", "ecdsa_521.openssh.pub", "");
        addSshAuth(builder, "ecdsa_521.pem.password", "ecdsa_521.openssh.pub", "secret");

        addSshAuth(builder, "ed25519.openssh", "ed25519.openssh.pub", "");
        addSshAuth(builder, "ed25519.openssh.password", "ed25519.openssh.pub", "secret");
        // Cannot convert ed25519 into PEM format.

        addSshAuth(builder, "rsa.openssh", "rsa.openssh.pub", "");
        addSshAuth(builder, "rsa.openssh.password", "rsa.openssh.pub", "secret");
        addSshAuth(builder, "rsa.pem", "rsa.openssh.pub", "");
        addSshAuth(builder, "rsa.pem.password", "rsa.openssh.pub", "secret");

        return builder.build();
    }

    private static void addSshAuth(Builder<Arguments> builder, String privateKeyFile, String publicKeyFile,
                                   String passphrase)
            throws IOException {
        final byte[] privateKeyBytes =
                Resources.toByteArray(GitMirrorSshAuthTest.class.getResource(privateKeyFile));
        final byte[] publicKeyBytes =
                Resources.toByteArray(GitMirrorSshAuthTest.class.getResource(publicKeyFile));
        final String privateKey = new String(privateKeyBytes, StandardCharsets.UTF_8).trim();
        final String publicKey = new String(publicKeyBytes, StandardCharsets.UTF_8).trim();

        builder.add(Arguments.of(
                privateKeyFile, // Use privateKeyFile as the project name.
                "git+ssh://github.com/line/centraldogma-authtest.git",
                Jackson.readTree(
                        '{' +
                        "  \"name\": \"" + credentialName(privateKeyFile, privateKeyFile) + "\"," +
                        "  \"type\": \"SSH_KEY\"," +
                        "  \"username\": \"git\"," +
                        "  \"publicKey\": \"" + Jackson.escapeText(publicKey) + "\"," +
                        "  \"privateKey\": \"" + Jackson.escapeText(privateKey) + "\"," +
                        "  \"passphrase\": \"" + passphrase + '"' +
                        '}')));
    }
}
