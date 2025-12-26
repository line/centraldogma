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
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialFile;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;

import java.util.Collection;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class GitMirrorHttpsAuthTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.pluginConfigs(new MirroringServicePluginConfig(true));
        }
    };

    // To make this test cover HTTPS authentication schemes, the following environment variables
    // must be set:
    //
    // - GITHUB_CD_AUTHTEST_USERNAME (optional; defaults to your system username)
    // - GITHUB_CD_AUTHTEST_PASSWORD (required when you want to test with password)
    // - GITHUB_CD_AUTHTEST_ACCESS_TOKEN (required when you want to test with GitHub PAT)
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

    /**
     * Your GitHub Personal Access Token. Please specify generated at https://github.com/settings/tokens
     */
    @Nullable
    private static final String GITHUB_ACCESS_TOKEN =
            System.getenv("GITHUB_CD_AUTHTEST_ACCESS_TOKEN");

    private static CentralDogma client;
    private static MirroringService mirroringService;

    @BeforeAll
    static void setUp() {
        client = dogma.client();
        mirroringService = dogma.mirroringService();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("arguments")
    @DisabledIf("noCredentials")
    void httpsAuth(String projName, String gitUri, JsonNode credential) {
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

    static boolean noCredentials() {
        return GITHUB_PASSWORD == null && GITHUB_ACCESS_TOKEN == null;
    }

    private static Collection<Arguments> arguments() throws Exception {
        final ImmutableSet.Builder<Arguments> builder = ImmutableSet.builder();

        if (GITHUB_PASSWORD != null) {
            builder.add(Arguments.of(
                    "https",
                    "git+https://github.com/line/centraldogma-authtest.git",
                    Jackson.readTree(
                            '{' +
                            "  \"name\": \"" + credentialName("https", "password-id") + "\"," +
                            "  \"type\": \"PASSWORD\"," +
                            "  \"username\": \"" + GITHUB_USERNAME + "\"," +
                            "  \"password\": \"" + Jackson.escapeText(GITHUB_PASSWORD) + '"' +
                            '}')));
        }

        if (GITHUB_ACCESS_TOKEN != null) {
            builder.add(Arguments.of(
                    "https",
                    "git+https://github.com/line/centraldogma-authtest.git",
                    Jackson.readTree(
                            '{' +
                            "  \"name\": \"" + credentialName("https", "access-token-id") + "\"," +
                            "  \"type\": \"ACCESS_TOKEN\"," +
                            "  \"accessToken\": \"" + Jackson.escapeText(GITHUB_ACCESS_TOKEN) + '"' +
                            '}')));
        }
        return builder.build();
    }
}
