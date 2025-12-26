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

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialFile;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;

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

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.storage.project.Project;
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
    void sshAuth(String projName, String gitUri, JsonNode credential) {
        client.createProject(projName).join();
        client.createRepository(projName, "main").join();

        // Add /credentials/{id}.json and /mirrors/{id}.json
        final String credentialName = credential.get("name").asText();
        client.forRepo(projName, Project.REPO_DOGMA)
              .commit("Add a mirror",
                      Change.ofJsonUpsert(credentialFile(credentialName), credential),
                      Change.ofJsonUpsert("/repos/main/mirrors/main.json",
                                          '{' +
                                          "  \"id\": \"main\"," +
                                          "  \"enabled\": true," +
                                          "  \"type\": \"single\"," +
                                          "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                          "  \"localRepo\": \"main\"," +
                                          "  \"localPath\": \"/\"," +
                                          "  \"remoteUri\": \"" + gitUri + "\"," +
                                          "  \"credentialName\": \"" + credentialName + '"' +
                                          '}'))
              .push().join();

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
