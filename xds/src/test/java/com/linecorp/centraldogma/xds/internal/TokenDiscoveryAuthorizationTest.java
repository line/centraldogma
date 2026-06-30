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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.endpoint.v3.EndpointDiscoveryServiceGrpc.EndpointDiscoveryServiceStub;
import io.grpc.stub.StreamObserver;

/**
 * Verifies that the control plane discovery API scopes the resources served to a client authenticated with an
 * application access token (instead of an mTLS certificate) to the groups its app identity has READ access to.
 */
final class TokenDiscoveryAuthorizationTest {

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
    void shouldScopeResourcesToReadableGroups() throws Exception {
        final String baseUri = "http://127.0.0.1:" + dogma.serverAddress().getPort();
        final WebClient admin = dogma.httpClient();

        // Create two groups, each with one cluster.
        createGroup(admin, "foo");
        createGroup(admin, "bar");
        createCluster(admin, "foo", "foo-cluster", cluster("groups/foo/clusters/foo-cluster", 1));
        createCluster(admin, "bar", "bar-cluster", cluster("groups/bar/clusters/bar-cluster", 1));

        // Register a non-admin app identity (token) and grant it READ access to 'foo' only.
        final String appToken = getAccessToken(WebClient.of(baseUri), USERNAME, PASSWORD, "xds-app", false);
        grantAppIdRole(admin, "foo", "READ");

        try (ClientFactory factory = ClientFactory.builder().build()) {
            // Connect a CDS stream authenticated with the application access token.
            final ClusterDiscoveryServiceStub client =
                    GrpcClients.builder(baseUri)
                               .factory(factory)
                               .auth(AuthToken.ofOAuth2(appToken))
                               .build(ClusterDiscoveryServiceStub.class);
            final BlockingQueue<DiscoveryResponse> queue = new ArrayBlockingQueue<>(4);
            final StreamObserver<DiscoveryRequest> requestObserver =
                    client.streamClusters(responseRecorder(queue));
            request(requestObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of());

            // Only the 'foo' cluster should be visible; the 'bar' cluster must be filtered out.
            awaitExact(queue, requestObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of(),
                       Set.of("groups/foo/clusters/foo-cluster"));

            // Granting READ on 'bar' must be reflected immediately on the already-open stream.
            grantAppIdRole(admin, "bar", "READ");
            awaitExact(queue, requestObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of(),
                       Set.of("groups/foo/clusters/foo-cluster", "groups/bar/clusters/bar-cluster"));

            // Revoking READ on 'foo' must also be reflected immediately: only 'bar' remains.
            revokeAppIdRole(admin, "foo");
            awaitExact(queue, requestObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of(),
                       Set.of("groups/bar/clusters/bar-cluster"));

            requestObserver.onCompleted();
        }
    }

    @Test
    void shouldServeFullSnapshotToUnauthenticatedClient() throws Exception {
        final String baseUri = "http://127.0.0.1:" + dogma.serverAddress().getPort();
        final WebClient admin = dogma.httpClient();

        // Two groups whose clusters must both be visible to an unauthenticated client.
        createGroup(admin, "open-a");
        createGroup(admin, "open-b");
        createCluster(admin, "open-a", "a-cluster", cluster("groups/open-a/clusters/a-cluster", 1));
        createCluster(admin, "open-b", "b-cluster", cluster("groups/open-b/clusters/b-cluster", 1));

        try (ClientFactory factory = ClientFactory.builder().build()) {
            // Connect a CDS stream WITHOUT any credentials (no token, no client certificate). Such a client is
            // served the full, unscoped snapshot for backward compatibility, so it sees every group's clusters.
            final ClusterDiscoveryServiceStub client =
                    GrpcClients.builder(baseUri)
                               .factory(factory)
                               .build(ClusterDiscoveryServiceStub.class);
            final BlockingQueue<DiscoveryResponse> queue = new ArrayBlockingQueue<>(4);
            final StreamObserver<DiscoveryRequest> requestObserver =
                    client.streamClusters(responseRecorder(queue));
            request(requestObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of());

            // Both groups' clusters are visible.
            awaitContains(queue, requestObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of(),
                          Set.of("groups/open-a/clusters/a-cluster", "groups/open-b/clusters/b-cluster"));

            requestObserver.onCompleted();
        }
    }

    @Test
    void shouldNotFilterEndpointsForScopedClient() throws Exception {
        final String baseUri = "http://127.0.0.1:" + dogma.serverAddress().getPort();
        final WebClient admin = dogma.httpClient();

        // Two groups, each with a cluster and an endpoint.
        createGroup(admin, "eds-foo");
        createGroup(admin, "eds-bar");
        createCluster(admin, "eds-foo", "c", cluster("groups/eds-foo/clusters/c", 1));
        createCluster(admin, "eds-bar", "c", cluster("groups/eds-bar/clusters/c", 1));
        createEndpoint(admin, "eds-foo", "e", loadAssignment("groups/eds-foo/endpoints/e", "127.0.0.1", 8080));
        createEndpoint(admin, "eds-bar", "e", loadAssignment("groups/eds-bar/endpoints/e", "127.0.0.1", 8080));

        // A non-admin app identity with READ access to 'eds-foo' only.
        final String appToken = getAccessToken(WebClient.of(baseUri), USERNAME, PASSWORD, "eds-app", false);
        grantRole(admin, "eds-foo", "eds-app", "READ");

        try (ClientFactory factory = ClientFactory.builder().build()) {
            final GrpcClientBuilder grpcBuilder =
                    GrpcClients.builder(baseUri).factory(factory).auth(AuthToken.ofOAuth2(appToken));

            // Clusters are scoped: only 'eds-foo' is readable, so 'eds-bar' is filtered out.
            final ClusterDiscoveryServiceStub cds = grpcBuilder.build(ClusterDiscoveryServiceStub.class);
            final BlockingQueue<DiscoveryResponse> cdsQueue = new ArrayBlockingQueue<>(4);
            final StreamObserver<DiscoveryRequest> cdsObserver = cds.streamClusters(responseRecorder(cdsQueue));
            request(cdsObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of());
            awaitExact(cdsQueue, cdsObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of(),
                       Set.of("groups/eds-foo/clusters/c"));
            cdsObserver.onCompleted();

            // Endpoints (EDS) are intentionally NOT access-controlled: the same client, which can read only
            // 'eds-foo', still sees the endpoints of 'eds-bar' as well. EDS is incremental, so the client must
            // name the endpoints it wants; naming the 'eds-bar' endpoint and receiving it proves it is not
            // filtered out by the client's lack of READ access to 'eds-bar'.
            final EndpointDiscoveryServiceStub eds = grpcBuilder.build(EndpointDiscoveryServiceStub.class);
            final BlockingQueue<DiscoveryResponse> edsQueue = new ArrayBlockingQueue<>(4);
            final StreamObserver<DiscoveryRequest> edsObserver =
                    eds.streamEndpoints(responseRecorder(edsQueue));
            // An EDS resource is keyed by the cluster name it serves, i.e. "groups/{group}/clusters/{id}".
            final Set<String> endpointNames =
                    Set.of("groups/eds-foo/clusters/e", "groups/eds-bar/clusters/e");
            request(edsObserver, Resources.V3.ENDPOINT_TYPE_URL, endpointNames);
            awaitContains(edsQueue, edsObserver, Resources.V3.ENDPOINT_TYPE_URL, endpointNames, endpointNames);
            edsObserver.onCompleted();
        }
    }

    private static StreamObserver<DiscoveryResponse> responseRecorder(BlockingQueue<DiscoveryResponse> queue) {
        return new StreamObserver<>() {
            @Override
            public void onNext(DiscoveryResponse value) {
                queue.add(value);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {}
        };
    }

    private static void request(StreamObserver<DiscoveryRequest> requestObserver, String typeUrl,
                                Set<String> resourceNames) {
        requestObserver.onNext(DiscoveryRequest.newBuilder()
                                               .setTypeUrl(typeUrl)
                                               .addAllResourceNames(resourceNames)
                                               .build());
    }

    private static Set<String> resourceNames(DiscoveryResponse response, String typeUrl) throws Exception {
        final Set<String> names = new HashSet<>();
        for (Any any : response.getResourcesList()) {
            if (Resources.V3.ENDPOINT_TYPE_URL.equals(typeUrl)) {
                names.add(ClusterLoadAssignment.parseFrom(any.getValue()).getClusterName());
            } else {
                names.add(Cluster.parseFrom(any.getValue()).getName());
            }
        }
        return names;
    }

    /**
     * Reads discovery responses (acking each one) until one whose resource names exactly equal {@code expected}
     * arrives.
     */
    private static void awaitExact(BlockingQueue<DiscoveryResponse> queue,
                                   StreamObserver<DiscoveryRequest> requestObserver,
                                   String typeUrl, Set<String> resourceNames, Set<String> expected)
            throws Exception {
        await(queue, requestObserver, typeUrl, resourceNames, names -> names.equals(expected), expected);
    }

    /**
     * Reads discovery responses (acking each one) until one whose resource names contain all of
     * {@code expected}.
     */
    private static void awaitContains(BlockingQueue<DiscoveryResponse> queue,
                                      StreamObserver<DiscoveryRequest> requestObserver,
                                      String typeUrl, Set<String> resourceNames, Set<String> expected)
            throws Exception {
        await(queue, requestObserver, typeUrl, resourceNames, names -> names.containsAll(expected), expected);
    }

    private interface NameMatcher {
        boolean matches(Set<String> names);
    }

    private static void await(BlockingQueue<DiscoveryResponse> queue,
                              StreamObserver<DiscoveryRequest> requestObserver, String typeUrl,
                              Set<String> resourceNames, NameMatcher matcher, Set<String> expected)
            throws Exception {
        for (int i = 0; i < 20; i++) {
            final DiscoveryResponse response = queue.poll(5, TimeUnit.SECONDS);
            assertThat(response).as("Expected a discovery response with resources %s", expected).isNotNull();
            // Ack every response (echoing the requested resource names) so the subscription stays alive and the
            // next snapshot update is pushed.
            requestObserver.onNext(DiscoveryRequest.newBuilder()
                                                   .setTypeUrl(typeUrl)
                                                   .addAllResourceNames(resourceNames)
                                                   .setVersionInfo(response.getVersionInfo())
                                                   .setResponseNonce(response.getNonce())
                                                   .build());
            if (matcher.matches(resourceNames(response, typeUrl))) {
                return;
            }
        }
        throw new AssertionError("Did not receive a discovery response with resources " + expected);
    }

    private static void grantAppIdRole(WebClient admin, String group, String role) {
        grantRole(admin, group, "xds-app", role);
    }

    private static void grantRole(WebClient admin, String group, String appId, String role) {
        final AggregatedHttpResponse response =
                admin.prepare()
                     .post("/api/v1/metadata/@xds/repos/" + group + "/roles/appIdentities")
                     .content(MediaType.JSON, "{\"id\":\"" + appId + "\",\"role\":\"" + role + "\"}")
                     .execute().aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    private static void revokeAppIdRole(WebClient admin, String group) {
        final AggregatedHttpResponse response =
                admin.prepare()
                     .delete("/api/v1/metadata/@xds/repos/" + group + "/roles/appIdentities/xds-app")
                     .execute().aggregate().join();
        assertThat(response.status()).isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);
    }

    private static void createGroup(WebClient admin, String group) {
        final AggregatedHttpResponse response =
                admin.prepare()
                     .post("/api/v1/xds/groups")
                     .queryParam("group_id", group)
                     .content(MediaType.JSON, "{\"name\":\"groups/" + group + "\"}")
                     .execute().aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    private static void createCluster(WebClient admin, String group, String clusterId, Cluster cluster)
            throws Exception {
        final AggregatedHttpResponse response =
                admin.prepare()
                     .post("/api/v1/xds/groups/" + group + "/clusters")
                     .queryParam("cluster_id", clusterId)
                     .content(MediaType.JSON, JSON_MESSAGE_MARSHALLER.writeValueAsString(cluster))
                     .execute().aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    private static void createEndpoint(WebClient admin, String group, String endpointId,
                                       ClusterLoadAssignment endpoint) throws Exception {
        final AggregatedHttpResponse response =
                admin.prepare()
                     .post("/api/v1/xds/groups/" + group + "/endpoints")
                     .queryParam("endpoint_id", endpointId)
                     .content(MediaType.JSON, JSON_MESSAGE_MARSHALLER.writeValueAsString(endpoint))
                     .execute().aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }
}
