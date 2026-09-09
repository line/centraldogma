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
package com.linecorp.centraldogma.client.armeria;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.PermissionException;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ArmeriaCentralDogmaTest {

    private static String regularUserAccessToken;

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected String accessToken() {
            return getAccessToken(WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                                  USERNAME, PASSWORD, true);
        }
    };

    @BeforeAll
    static void setUp() {
        regularUserAccessToken = getAccessToken(dogma.httpClient(), USERNAME2, PASSWORD2,
                                                "regularUser", false);
    }

    @Test
    void regularUserCannotPushFileToMetaRepository() throws UnknownHostException {
        assertRegularUserCannotPushToMetaRepository(
                Change.ofJsonUpsert("/bar.json", "{ \"a\": \"b\" }"));
    }

    @Test
    void regularUserCannotPushMirrorsJsonFileToMetaRepository() throws UnknownHostException {
        assertRegularUserCannotPushToMetaRepository(
                Change.ofJsonUpsert("/repos/foo/mirrors/foo.json", "{}"));
    }

    @Test
    void regularUserCannotPushCredentialJsonFilesToMetaRepository() throws UnknownHostException {
        assertRegularUserCannotPushToMetaRepository(
                Change.ofJsonUpsert("/credentials/foo.json", "{}"),
                Change.ofJsonUpsert("/repos/foo/credentials/bar.json", "{}"));
    }

    private static void assertRegularUserCannotPushToMetaRepository(Change<?>... changes)
            throws UnknownHostException {
        final CentralDogma client = newRegularUserClient();

        // The dogma repository is system-admin-only, so a regular user is rejected before its
        // changes are evaluated.
        assertThatThrownBy(() -> client.forRepo("foo", Project.REPO_DOGMA)
                                       .commit("summary", changes)
                                       .push()
                                       .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PermissionException.class);
    }

    private static CentralDogma newRegularUserClient() throws UnknownHostException {
        return new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .accessToken(regularUserAccessToken)
                .build();
    }
}
