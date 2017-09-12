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

package com.linecorp.centraldogma.it.mirror.git;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableSet;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.it.TestConstants;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

@RunWith(Parameterized.class)
public class GitMirrorAuthTest {

    @ClassRule
    public static final CentralDogmaRule rule = new CentralDogmaRule();

    // To make this test cover all supported authentication schemes, the properties defined below need to be
    // set via System properties.
    //
    // Assuming that:
    // - you have read access to https://github.com/line/centraldogma-authtest
    // - your system username is identical to your GitHub.com username
    // - your SSH private key and public key are located at:
    //   - $HOME/.ssh/id_rsa
    //   - $HOME/.ssh/id_rsa.pub
    //
    // The only properties you need to set to run this test are:
    // - git.password: your GitHub.com password or personal token if you are using 2-factor authentication
    // - git.passphrase: the passphrase of your encrypted SSH private key
    //                   (not needed if your private key is unencrypted)

    /**
     * The hostname of the git server.
     */
    private static final String GIT_HOST = System.getProperty("git.host", "github.com");

    /**
     * The port number of the git-over-HTTPS server.
     */
    private static final String GIT_PORT_HTTPS = System.getProperty("git.httpsPort", "443");

    /**
     * The port number of the git-over-SSH server.
     */
    private static final String GIT_PORT_SSH = System.getProperty("git.sshPort", "22");

    /**
     * The path of the remote git repository. e.g. /foo/bar.git
     */
    private static final String GIT_PATH = System.getProperty("git.path", "/line/centraldogma-authtest.git");

    /**
     * The username of the git-over-HTTPS server.
     */
    private static final String GIT_USERNAME_HTTPS = System.getProperty(
            "git.httpsUsername", System.getProperty("user.name"));

    /**
     * The username of the git-over-SSH server.
     */
    private static final String GIT_USERNAME_SSH = System.getProperty("git.sshUsername", "git");

    /**
     * The password. Required for password authentication.
     * If you are using 2-factor authentication in GitHub.com, you'll have to generate a personal token
     * and specify it here. See: https://github.com/settings/tokens
     */
    private static final String GIT_PASSWORD = System.getProperty("git.password");

    /**
     * The path to the private key file. Required for public key authentication.
     */
    private static final String GIT_PRIVATE_KEY = System.getProperty(
            "git.privateKey",
            System.getProperty("user.home") + File.separator + ".ssh" + File.separator + "id_rsa");

    /**
     * The path to the public key file. Required for public key authentication.
     */
    private static final String GIT_PUBLIC_KEY = System.getProperty("git.publicKey", GIT_PRIVATE_KEY + ".pub");

    /**
     * The passphrase of the private key file.
     */
    private static final String GIT_PASSPHRASE = System.getProperty("git.passphrase");

    private static CentralDogma client;
    private static MirroringService mirroringService;

    @Parameters(name = "{0}")
    public static Collection<Object[]> parameters() throws Exception {
        final ImmutableSet.Builder<Object[]> builder = ImmutableSet.builder();
        if (GIT_PASSWORD != null) {
            builder.add(new Object[] {
                    "https",
                    "git+https://" + GIT_HOST + ':' + GIT_PORT_HTTPS + GIT_PATH,
                    Jackson.readTree(
                            '{' +
                            "  \"type\": \"password\"," +
                            "  \"hostnamePatterns\": [ \"^.*$\" ]," +
                            "  \"username\": \"" + GIT_USERNAME_HTTPS + "\"," +
                            "  \"password\": \"" + Jackson.escapeText(GIT_PASSWORD) + '"' +
                            '}')
            });
        }

        // Test Git-over-SSH only when the public key and private key files are readable.
        if (new File(GIT_PUBLIC_KEY).canRead() &&
            new File(GIT_PRIVATE_KEY).canRead()) {
            final byte[] privateKeyBytes = Files.readAllBytes(Paths.get(GIT_PRIVATE_KEY));
            final byte[] publicKeyBytes = Files.readAllBytes(Paths.get(GIT_PUBLIC_KEY));
            final String privateKey = new String(privateKeyBytes, StandardCharsets.UTF_8);
            final String publicKey = new String(publicKeyBytes, StandardCharsets.UTF_8);

            // Test Git-over-SSH only when:
            // - the private key passphrase is specified or
            // - the private key is unencrypted.
            if (GIT_PASSPHRASE != null ||
                !KeyPair.load(new JSch(), privateKeyBytes, publicKeyBytes).isEncrypted()) {

                final String passphraseProperty;
                if (GIT_PASSPHRASE != null) {
                    passphraseProperty = "\"passphrase\": \"" + Jackson.escapeText(GIT_PASSPHRASE) + '"';
                } else {
                    passphraseProperty = "\"passphrase\": null";
                }

                builder.add(new Object[] {
                        "ssh",
                        "git+ssh://" + GIT_HOST + ':' + GIT_PORT_SSH + GIT_PATH,
                        Jackson.readTree(
                                '{' +
                                "  \"type\": \"public_key\"," +
                                "  \"hostnamePatterns\": [ \"^.*$\" ]," +
                                "  \"username\": \"" + GIT_USERNAME_SSH + "\"," +
                                "  \"publicKey\": \"" + Jackson.escapeText(publicKey) + "\"," +
                                "  \"privateKey\": \"" + Jackson.escapeText(privateKey) + "\"," +
                                passphraseProperty +
                                '}')
                });
            }
        }

        return builder.build();
    }

    @BeforeClass
    public static void init() {
        client = rule.client();
        mirroringService = rule.mirroringService();
    }

    @Rule
    public final TemporaryFolder gitRepoDir = new TemporaryFolder();

    private final String projName;
    private final String gitUri;
    private final JsonNode credential;

    public GitMirrorAuthTest(String projName, String gitUri, JsonNode credential) {
        this.projName = projName;
        this.gitUri = gitUri;
        this.credential = credential;
    }

    @Before
    public void initDogmaRepo() throws Exception {
        client.createProject(projName).join();
    }

    @After
    public void destroyDogmaRepo() throws Exception {
        client.removeProject(projName).join();
    }

    @Test
    public void testAuth() throws Exception {
        // Add /credentials.json and /mirrors.json
        final ArrayNode credentials = JsonNodeFactory.instance.arrayNode().add(credential);
        client.push(projName, Project.REPO_META, Revision.HEAD, TestConstants.AUTHOR, "Add a mirror",
                    Change.ofJsonUpsert("/credentials.json", credentials),
                    Change.ofJsonUpsert("/mirrors.json",
                                        "[{" +
                                        "  \"type\": \"single\"," +
                                        "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                        "  \"localRepo\": \"main\"," +
                                        "  \"localPath\": \"/\"," +
                                        "  \"remoteUri\": \"" + gitUri + '"' +
                                        "}]")).join();

        // Try to perform mirroring to see if authentication works as expected.
        mirroringService.mirror().join();
    }
}
