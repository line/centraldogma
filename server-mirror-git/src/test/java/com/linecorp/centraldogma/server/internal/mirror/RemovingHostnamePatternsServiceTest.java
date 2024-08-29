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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class RemovingHostnamePatternsServiceTest {

    private static final String TEST_PROJ = "fooProj";

    // The real key pair generated using:
    //
    //   ssh-keygen -t rsa -b 768 -N sesame
    //
    private static final String PUBLIC_KEY =
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAYQCmkW9HjZE5q0EM06MUWXYFTNTi" +
            "KkfYD/pH2GwJw6yi20Gi0TzjJ6YBLueU48vxkwWmw6sTOEuBxtzefTxs4kQuatev" +
            "uXn7tWX9fhSIAEp+zdyQY7InyCqfHFwRwswemCM= trustin@localhost";

    private static final String PRIVATE_KEY =
            "-----BEGIN RSA PRIVATE KEY-----\n" +
            "Proc-Type: 4,ENCRYPTED\n" +
            "DEK-Info: AES-128-CBC,C35856D3C524AA2FD32D878F4409B97E\n" +
            '\n' +
            "X3HRmqg2bUqfqxkWjHsr4KeN1UyN5QbypGd7Jov/nDSyiIWe4zPJD/3oji0xOK+h\n" +
            "Lxq+c8DDu7ItpC6dwe5WexcyIKGF7WqlkqeEhVM3VOkQtbpbdnb7bA8mLja2unMW\n" +
            "bFLgQiTF1Y8SlG4Q70N0iY638AeIG/ZUU14LSBFSQDkrtZ+f7bhIhVDDavANMF+B\n" +
            "+eiQ4u3W59Cpbm83AfzqotrPXuBusfyBjH7Wfj0XRvOGRjTQT0jXIWWpLqnIy5ms\n" +
            "HNGlMoJElUQuPpbQUiFvmqiMj40r9V/Wx/8+GciADOs4FsTvGFKIcouWDhjIWg0b\n" +
            "DKFqV/Hw/AjkAafkySxxmk1+EIen4XfkghtlWLwT2Xp4RtJXYiVC9q9483jDv3+Z\n" +
            "iTa5rjFuro4WJkDZp6/N6l+/HcbBXL8L6y66xsJwP+6GLuDLpXjGZrneV1ip2dtG\n" +
            "BQzvlgCOr9pTAa4Ar7MC3E2C6+qPhOwO4B/f1cigwRaEB92MHz5gJsITU3xVfTjV\n" +
            "yf4THKipBDxqnET6F2FMZJFolVzFEXDaCFNC1TjBqS0+A8KaMcO/lXjJxtfvV37l\n" +
            "zmB/ey0dZ8WBCazCp9OX3dYgNkVR1yYNlJWOGJS8Cwc=\n" +
            "-----END RSA PRIVATE KEY-----";

    private static final String PASSPHRASE = "sesame";

    private static final String PUBLIC_KEY_CREDENTIAL_FORMAT =
            '{' +
            "  \"id\": \"%s\"," +
            "  \"type\": \"public_key\"," +
            "  %s" +
            "  \"username\": \"trustin\"," +
            "  \"publicKey\": \"" + Jackson.escapeText(PUBLIC_KEY) + "\"," +
            "  \"privateKey\": \"" + Jackson.escapeText(PRIVATE_KEY) + "\"," +
            "  \"passphrase\": \"" + Jackson.escapeText(PASSPHRASE) + '"' +
            '}';

    private static final String PASSWORD_CREDENTIAL_FORMAT =
            '{' +
            "  \"id\": \"%s\"," +
            "  \"type\": \"password\"," +
            "  %s" +
            "  \"username\": \"trustin\"," +
            "  \"password\": \"sesame\"" +
            '}';

    // A credential with duplicate ID
    static final String ACCESS_TOKEN_CREDENTIAL_FORMAT =
            '{' +
            "  \"id\": \"%s\"," +
            "  \"type\": \"access_token\"," +
            "  %s" +
            "  \"accessToken\": \"sesame\"" +
            '}';

    private static final String HOSTNAME_PATTERNS =
            "  \"hostnamePatterns\": [" +
            "    \"^git\\\\.foo\\\\.com$\"" +
            "  ],";

    @RegisterExtension
    static ProjectManagerExtension projectManagerExtension = new ProjectManagerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void afterExecutorStarted() {
            final ProjectManager projectManager = projectManagerExtension.projectManager();
            projectManager.create(TEST_PROJ, Author.SYSTEM);
        }
    };

    @CsvSource(value = {
            HOSTNAME_PATTERNS, "''", "\"hostnamePatterns\": [],", "\"hostnamePatterns\": null,"
    }, delimiter = ';') // string contains ',' so change the delimiter.
    @ParameterizedTest
    void removeHostnamePatterns(String hostnamePatterns) throws Exception {
        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.get(TEST_PROJ);

        final String publicKeyCredential =
                String.format(PUBLIC_KEY_CREDENTIAL_FORMAT, "credential-1", hostnamePatterns);
        final String passwordCredential =
                String.format(PASSWORD_CREDENTIAL_FORMAT, "credential-2", hostnamePatterns);
        final String accessTokenCredential =
                String.format(ACCESS_TOKEN_CREDENTIAL_FORMAT, "credential-3", hostnamePatterns);
        final Change<JsonNode> change1 =
                Change.ofJsonUpsert("/credentials/credential-1.json", publicKeyCredential);
        final Change<JsonNode> change2 =
                Change.ofJsonUpsert("/credentials/credential-2.json", passwordCredential);
        final Change<JsonNode> change3 =
                Change.ofJsonUpsert("/credentials/credential-3.json", accessTokenCredential);

        project.metaRepo().commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                                  "Create credentials.", change1, change2, change3).join();
        final RemovingHostnamePatternsService service = new RemovingHostnamePatternsService(
                projectManager, projectManagerExtension.executor());
        service.start();

        final Map<String, Entry<?>> entries = project.metaRepo()
                                                     .find(Revision.HEAD, "/credentials/*.json")
                                                     .join();

        assertThat(entries).hasSize(3);
        final Entry<?> entry1 = entries.get("/credentials/credential-1.json");
        assertCredential(entry1, String.format(PUBLIC_KEY_CREDENTIAL_FORMAT, "credential-1", ""));
        MirrorCredential mirrorCredential = Jackson.treeToValue(entry1.contentAsJson(), MirrorCredential.class);
        assertThat(mirrorCredential.hostnamePatterns()).isEmpty();
        assertThat(mirrorCredential.id()).isEqualTo("credential-1");

        final Entry<?> entry2 = entries.get("/credentials/credential-2.json");
        assertCredential(entry2, String.format(PASSWORD_CREDENTIAL_FORMAT, "credential-2", ""));
        mirrorCredential = Jackson.treeToValue(entry2.contentAsJson(), MirrorCredential.class);
        assertThat(mirrorCredential.hostnamePatterns()).isEmpty();
        assertThat(mirrorCredential.id()).isEqualTo("credential-2");

        final Entry<?> entry3 = entries.get("/credentials/credential-3.json");
        assertCredential(entry3, String.format(ACCESS_TOKEN_CREDENTIAL_FORMAT, "credential-3", ""));
        mirrorCredential = Jackson.treeToValue(entry3.contentAsJson(), MirrorCredential.class);
        assertThat(mirrorCredential.hostnamePatterns()).isEmpty();
        assertThat(mirrorCredential.id()).isEqualTo("credential-3");

    }

    private static void assertCredential(Entry<?> entry, String credential) throws JsonParseException {
        assertThatJson(entry.contentAsJson()).isEqualTo(
                ((ObjectNode) Jackson.readTree(credential)).without("hostnamePatterns"));
    }
}
