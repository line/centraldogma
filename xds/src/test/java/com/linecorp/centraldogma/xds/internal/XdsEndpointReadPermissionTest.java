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
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.cluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.loadAssignment;
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
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * Verifies that EDS (endpoint) resources can be read by any authenticated user without a repository role,
 * while the other resource types (e.g. clusters) remain gated by the READ repository role.
 */
final class XdsEndpointReadPermissionTest {

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
    void shouldReadEndpointsWithoutReadRole() throws Exception {
        final String baseUri = "http://127.0.0.1:" + dogma.serverAddress().getPort();
        // A non-admin app identity 'reader' with no role on the group.
        final String readerToken =
                getAccessToken(WebClient.of(baseUri), USERNAME, PASSWORD, "reader", false);
        final WebClient admin = dogma.httpClient();
        final WebClient reader = WebClient.builder(baseUri).auth(AuthToken.ofOAuth2(readerToken)).build();

        // Admin creates the group with one cluster and one endpoint.
        assertThat(createGroup(admin, "foo").status()).isEqualTo(HttpStatus.OK);
        assertThat(createCluster(admin, "foo", "c1", cluster("groups/foo/clusters/c1", 1)).status())
                .isEqualTo(HttpStatus.OK);
        final ClusterLoadAssignment endpoint = loadAssignment("groups/foo/endpoints/e1", "127.0.0.1", 8080);
        assertThat(createEndpoint(admin, "foo", "e1", endpoint).status()).isEqualTo(HttpStatus.OK);

        // 'reader' has no role on 'foo', yet it can list and read the endpoints.
        final AggregatedHttpResponse list =
                reader.get("/api/v1/xds/groups/foo/endpoints").aggregate().join();
        assertThat(list.status()).isEqualTo(HttpStatus.OK);
        assertThat(list.contentUtf8()).contains("/endpoints/e1.yaml");

        final AggregatedHttpResponse get =
                reader.get("/api/v1/xds/groups/foo/endpoints/e1").aggregate().join();
        assertThat(get.status()).isEqualTo(HttpStatus.OK);
        assertThat(get.contentUtf8()).contains("/endpoints/e1.yaml").contains("127.0.0.1");

        final String clusterPath = "/api/v1/projects/@xds/repos/foo/contents/clusters/c1.yaml?revision=head";
        final AggregatedHttpResponse cluster = reader.get(clusterPath).aggregate().join();
        assertThat(cluster.status()).isEqualTo(HttpStatus.FORBIDDEN);

        // Sanity check that the path is valid and the FORBIDDEN above is due to the missing READ role, not a
        // bad path: the admin can read the very same cluster.
        final AggregatedHttpResponse adminCluster = admin.get(clusterPath).aggregate().join();
        assertThat(adminCluster.status()).isEqualTo(HttpStatus.OK);
    }

    private static AggregatedHttpResponse createGroup(WebClient client, String group) {
        return client.prepare()
                     .post("/api/v1/xds/groups")
                     .queryParam("group_id", group)
                     .content(MediaType.JSON, "{\"name\":\"groups/" + group + "\"}")
                     .execute().aggregate().join();
    }

    private static AggregatedHttpResponse createCluster(WebClient client, String group, String clusterId,
                                                        Cluster cluster) throws Exception {
        return client.prepare()
                     .post("/api/v1/xds/groups/" + group + "/clusters")
                     .queryParam("cluster_id", clusterId)
                     .content(MediaType.JSON, JSON_MESSAGE_MARSHALLER.writeValueAsString(cluster))
                     .execute().aggregate().join();
    }

    private static AggregatedHttpResponse createEndpoint(WebClient client, String group, String endpointId,
                                                         ClusterLoadAssignment endpoint) throws Exception {
        return client.prepare()
                     .post("/api/v1/xds/groups/" + group + "/endpoints")
                     .queryParam("endpoint_id", endpointId)
                     .content(MediaType.JSON, JSON_MESSAGE_MARSHALLER.writeValueAsString(endpoint))
                     .execute().aggregate().join();
    }
}
