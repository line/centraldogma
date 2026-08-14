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
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.cluster;
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

import io.envoyproxy.envoy.config.cluster.v3.Cluster;

final class XdsWritePermissionTest {

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
    void shouldRequireWriteRoleToModifyResources() throws Exception {
        final String baseUri = "http://127.0.0.1:" + dogma.serverAddress().getPort();
        // A non-admin app identity 'writer'.
        final String writerToken =
                getAccessToken(WebClient.of(baseUri), USERNAME, PASSWORD, "writer", false);
        final WebClient admin = dogma.httpClient();
        final WebClient writer = WebClient.builder(baseUri).auth(AuthToken.ofOAuth2(writerToken)).build();

        // Admin creates the group.
        assertThat(createGroup(admin, "foo").status()).isEqualTo(HttpStatus.OK);

        final Cluster cluster = cluster("groups/foo/clusters/c1", 1);

        // 'writer' has no role on 'foo' yet -> create and delete are both denied.
        AggregatedHttpResponse response = createCluster(writer, "foo", "c1", cluster);
        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deleteCluster(writer, "foo", "c1").status()).isEqualTo(HttpStatus.FORBIDDEN);

        // The denied request must not have created the resource.
        assertThat(getClusterStatus(admin, "foo", "c1")).isEqualTo(HttpStatus.NOT_FOUND);

        // Grant WRITE to 'writer'.
        final AggregatedHttpResponse roleResponse =
                admin.prepare()
                     .post("/api/v1/metadata/@xds/repos/foo/roles/appIdentities")
                     .content(MediaType.JSON, "{\"id\":\"writer\",\"role\":\"WRITE\"}")
                     .execute().aggregate().join();
        assertThat(roleResponse.status()).isEqualTo(HttpStatus.OK);

        // Now 'writer' can create the cluster.
        response = createCluster(writer, "foo", "c1", cluster);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        // The resource is now actually present.
        assertThat(getClusterStatus(admin, "foo", "c1")).isEqualTo(HttpStatus.OK);
    }

    @Test
    void readRoleIsInsufficientAndAdminCanWrite() throws Exception {
        final String baseUri = "http://127.0.0.1:" + dogma.serverAddress().getPort();
        final WebClient admin = dogma.httpClient();
        // A reader with only the READ role and a group admin with the ADMIN role.
        final WebClient reader = client(baseUri, "reader");
        final WebClient groupAdmin = client(baseUri, "group-admin");

        assertThat(createGroup(admin, "bar").status()).isEqualTo(HttpStatus.OK);
        final Cluster cluster = cluster("groups/bar/clusters/c1", 1);

        // A READ role is insufficient to modify resources.
        grantRole(admin, "bar", "reader", "READ");
        assertThat(createCluster(reader, "bar", "c1", cluster).status()).isEqualTo(HttpStatus.FORBIDDEN);
        // The denied request must not have created the resource.
        assertThat(getClusterStatus(admin, "bar", "c1")).isEqualTo(HttpStatus.NOT_FOUND);

        // An ADMIN role can modify resources (ADMIN implies WRITE).
        grantRole(admin, "bar", "group-admin", "ADMIN");
        final AggregatedHttpResponse response = createCluster(groupAdmin, "bar", "c1", cluster);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(getClusterStatus(admin, "bar", "c1")).isEqualTo(HttpStatus.OK);
    }

    private static HttpStatus getClusterStatus(WebClient client, String group, String clusterId) {
        return client.get("/api/v1/projects/@xds/repos/" + group + "/contents/clusters/" + clusterId +
                          ".yaml?revision=head")
                     .aggregate().join().status();
    }

    private static WebClient client(String baseUri, String appId) throws Exception {
        final String token = getAccessToken(WebClient.of(baseUri), USERNAME, PASSWORD, appId, false);
        return WebClient.builder(baseUri).auth(AuthToken.ofOAuth2(token)).build();
    }

    private static void grantRole(WebClient admin, String group, String appId, String role) {
        final AggregatedHttpResponse response =
                admin.prepare()
                     .post("/api/v1/metadata/@xds/repos/" + group + "/roles/appIdentities")
                     .content(MediaType.JSON, "{\"id\":\"" + appId + "\",\"role\":\"" + role + "\"}")
                     .execute().aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    private static AggregatedHttpResponse createGroup(WebClient client, String group) {
        return client.prepare()
                     .post("/api/v1/xds/groups")
                     .queryParam("group_id", group)
                     .execute().aggregate().join();
    }

    private static AggregatedHttpResponse createCluster(WebClient client, String group, String clusterId,
                                                        Cluster cluster) throws Exception {
        return client.prepare()
                     .post("/api/v1/xds/groups/" + group + "/clusters")
                     .queryParam("cluster_id", clusterId)
                     .content(MediaType.parse("application/yaml"), XdsTestUtil.toYaml(cluster))
                     .execute().aggregate().join();
    }

    private static AggregatedHttpResponse deleteCluster(WebClient client, String group, String clusterId) {
        return client.prepare()
                     .delete("/api/v1/xds/groups/" + group + "/clusters/" + clusterId)
                     .execute().aggregate().join();
    }
}
