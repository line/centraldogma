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
package com.linecorp.centraldogma.xds.internal;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

/**
 * Verifies that deleting an xDS group requires the ADMIN repository role on the group (or system administrator
 * privileges); a WRITE role, a READ role or no role at all is insufficient.
 */
final class XdsGroupDeletePermissionTest {

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
                                  USERNAME, PASSWORD, "adminApp", true);
        }
    };

    @Test
    void shouldRequireAdminRoleToDeleteGroup() throws Exception {
        final String baseUri = "http://127.0.0.1:" + dogma.serverAddress().getPort();
        final WebClient admin = dogma.httpClient();
        // Three distinct non-admin app identities so each role grant is independent.
        final WebClient noRole = client(baseUri, "no-role");
        final WebClient writer = client(baseUri, "writer");
        final WebClient groupAdmin = client(baseUri, "group-admin");

        // The (system admin) admin creates the group.
        assertThat(createGroup(admin, "foo").status()).isEqualTo(HttpStatus.OK);

        // A principal with no role cannot delete the group.
        assertThat(deleteGroup(noRole, "foo").headers().get("grpc-status")).isEqualTo("7"); // PERMISSION_DENIED

        // A WRITE role is insufficient; deletion requires ADMIN.
        grantRole(admin, "foo", "writer", "WRITE");
        assertThat(deleteGroup(writer, "foo").headers().get("grpc-status")).isEqualTo("7");

        // An ADMIN role can delete the group.
        grantRole(admin, "foo", "group-admin", "ADMIN");
        final AggregatedHttpResponse deleted = deleteGroup(groupAdmin, "foo");
        assertThat(deleted.status()).isEqualTo(HttpStatus.OK);
        assertThat(deleted.headers().get("grpc-status")).isEqualTo("0");

        // The group is really gone.
        assertThat(deleteGroup(admin, "foo").status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static WebClient client(String baseUri, String appId) throws Exception {
        final String token = getAccessToken(WebClient.of(baseUri), USERNAME, PASSWORD, appId, false);
        return WebClient.builder(baseUri).auth(AuthToken.ofOAuth2(token)).build();
    }

    private static AggregatedHttpResponse createGroup(WebClient client, String group) {
        return client.prepare()
                     .post("/api/v1/xds/groups")
                     .queryParam("group_id", group)
                     .content(MediaType.JSON, "{\"name\":\"groups/" + group + "\"}")
                     .execute().aggregate().join();
    }

    private static AggregatedHttpResponse deleteGroup(WebClient client, String group) {
        return client.prepare()
                     .delete("/api/v1/xds/groups/" + group)
                     .execute().aggregate().join();
    }

    private static void grantRole(WebClient admin, String group, String appId, String role) {
        final AggregatedHttpResponse response =
                admin.prepare()
                     .post("/api/v1/metadata/@xds/repos/" + group + "/roles/appIdentities")
                     .content(MediaType.JSON, "{\"id\":\"" + appId + "\",\"role\":\"" + role + "\"}")
                     .execute().aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }
}
