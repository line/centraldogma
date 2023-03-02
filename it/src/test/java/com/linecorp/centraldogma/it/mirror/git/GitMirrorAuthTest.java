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

import static com.google.common.base.MoreObjects.firstNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class GitMirrorAuthTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.mirroringEnabled(true);
        }
    };

    // To make this test cover all supported authentication schemes, the following environment variables
    // must be set:
    //
    // - GITHUB_CD_AUTHTEST_USERNAME (optional; defaults to your system username)
    // - GITHUB_CD_AUTHTEST_PASSWORD (required)
    //
    // AND:
    //
    // - You must have the read access to https://github.com/line/centraldogma-authtest
    //   - Please contact the project maintainers if you don't.
    //

    /**
     * Your GitHub username.
     */
    private static final String GITHUB_USERNAME = firstNonNull(
            System.getenv("GITHUB_CD_AUTHTEST_USERNAME"),
            System.getProperty("user.name"));

    /**
     * Your GitHub password. If you are using 2-factor authentication in GitHub.com,
     * specify the personal access token generated at https://github.com/settings/tokens
     */
    @Nullable
    private static final String GITHUB_PASSWORD =
            System.getenv("GITHUB_CD_AUTHTEST_PASSWORD");

    private static CentralDogma client;
    private static MirroringService mirroringService;

    @BeforeAll
    static void setUp() {
        client = dogma.client();
        mirroringService = dogma.mirroringService();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("arguments")
    void auth(String projName, String gitUri, JsonNode credential) {
        client.createProject(projName).join();
        client.createRepository(projName, "main").join();

        // Add /credentials.json and /mirrors.json
        final ArrayNode credentials = JsonNodeFactory.instance.arrayNode().add(credential);
        client.forRepo(projName, Project.REPO_META)
              .commit("Add a mirror",
                      Change.ofJsonUpsert("/credentials.json", credentials),
                      Change.ofJsonUpsert("/mirrors.json",
                                          "[{" +
                                          "  \"type\": \"single\"," +
                                          "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                          "  \"localRepo\": \"main\"," +
                                          "  \"localPath\": \"/\"," +
                                          "  \"remoteUri\": \"" + gitUri + '"' +
                                          "}]"))
              .push().join();

        // Try to perform mirroring to see if authentication works as expected.
        mirroringService.mirror().join();

        client.removeProject(projName).join();
    }

    private static Collection<Arguments> arguments() throws Exception {
        final ImmutableSet.Builder<Arguments> builder = ImmutableSet.builder();
        if (GITHUB_PASSWORD != null) {
            builder.add(Arguments.of(
                    "https",
                    "git+https://github.com/line/centraldogma-authtest.git",
                    Jackson.readTree(
                            '{' +
                            "  \"type\": \"password\"," +
                            "  \"hostnamePatterns\": [ \"^.*$\" ]," +
                            "  \"username\": \"" + GITHUB_USERNAME + "\"," +
                            "  \"password\": \"" + Jackson.escapeText(GITHUB_PASSWORD) + '"' +
                            '}')));
        }

        // Test Git-over-SSH as well with the read-only GitHub deploy key of the test repository.
        //
        // Note to security auditors:
        //
        //   Do not report any security issues about the SSH key pair being used in this test.
        //   It is intentionally checked in to the source code repository and used only for
        //   accessing an empty read-only private repository dedicated to test if SSH authentication works.
        //   We are very sure that it has access to no other repositories.
        //
        final byte[] privateKeyBytes = Resources.toByteArray(GitMirrorAuthTest.class.getResource("test.pem"));
        final byte[] publicKeyBytes = Resources.toByteArray(GitMirrorAuthTest.class.getResource("test.pub"));
        final String privateKey = new String(privateKeyBytes, StandardCharsets.UTF_8);
        final String publicKey = new String(publicKeyBytes, StandardCharsets.UTF_8);

        builder.add(Arguments.of(
                "ssh",
                "git+ssh://github.com/line/centraldogma-authtest.git",
                Jackson.readTree(
                        '{' +
                        "  \"type\": \"public_key\"," +
                        "  \"hostnamePatterns\": [ \"^.*$\" ]," +
                        "  \"username\": \"git\"," +
                        "  \"publicKey\": \"" + Jackson.escapeText(publicKey) + "\"," +
                        "  \"privateKey\": \"" + Jackson.escapeText(privateKey) + "\"," +
                        "  \"passphrase\": \"secret\"" +
                        '}')));

        return builder.build();
    }
}
