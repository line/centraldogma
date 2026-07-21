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
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.exampleListener;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.routeConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.Message;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientTlsConfig;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.SignedCertificateExtension;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.TlsConfig;
import com.linecorp.centraldogma.server.auth.MtlsConfig;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.listener.v3.ListenerDiscoveryServiceGrpc.ListenerDiscoveryServiceStub;
import io.envoyproxy.envoy.service.route.v3.RouteDiscoveryServiceGrpc.RouteDiscoveryServiceStub;
import io.grpc.stub.StreamObserver;

/**
 * Verifies that the control plane discovery API scopes the resources served to an mTLS-authenticated client to
 * the groups its app identity has READ access to.
 */
final class MtlsDiscoveryAuthorizationTest {

    private static final String CLIENT_CN = "xds-client";

    @Order(1)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension();

    @Order(2)
    @RegisterExtension
    static final SelfSignedCertificateExtension ca = new SelfSignedCertificateExtension();

    @Order(3)
    @RegisterExtension
    static final SignedCertificateExtension clientCert = new SignedCertificateExtension(CLIENT_CN, ca, false);

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.port(0, SessionProtocol.HTTPS);
            builder.tls(new TlsConfig(serverCert.certificateFile(), serverCert.privateKeyFile(),
                                      null, null, null));
            builder.mtlsConfig(new MtlsConfig(true, ImmutableList.of(ca.certificateFile())));
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected String accessToken() {
            final WebClient client = WebClient.builder("https://127.0.0.1:" + dogma.serverAddress().getPort())
                                              .factory(ClientFactory.insecure())
                                              .build();
            return getAccessToken(client, USERNAME, PASSWORD, "adminId", true, true, false);
        }

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.factory(ClientFactory.insecure());
        }
    };

    @Test
    void shouldScopeResourcesToReadableGroups() throws Exception {
        final WebClient admin = dogma.httpClient();

        // Create two groups, each with one cluster, listener and route.
        createGroup(admin, "foo");
        createGroup(admin, "bar");
        createCluster(admin, "foo", "foo-cluster", cluster("groups/foo/clusters/foo-cluster", 1));
        createCluster(admin, "bar", "bar-cluster", cluster("groups/bar/clusters/bar-cluster", 1));
        createListener(admin, "foo", "foo-listener",
                       exampleListener("ignored", "groups/foo/routes/foo-route", "foo-stat"));
        createListener(admin, "bar", "bar-listener",
                       exampleListener("ignored", "groups/bar/routes/bar-route", "bar-stat"));
        createRoute(admin, "foo", "foo-route",
                    routeConfiguration("ignored", "groups/foo/clusters/foo-cluster"));
        createRoute(admin, "bar", "bar-route",
                    routeConfiguration("ignored", "groups/bar/clusters/bar-cluster"));

        // Register a certificate app identity and grant it READ access to 'foo' only.
        final AggregatedHttpResponse appIdResponse =
                admin.prepare()
                     .post("/api/v1/appIdentities")
                     .queryParam("appId", "xds-app")
                     .queryParam("type", "CERTIFICATE")
                     .queryParam("certificateId", CLIENT_CN)
                     .queryParam("isSystemAdmin", "false")
                     .execute().aggregate().join();
        assertThat(appIdResponse.status()).isIn(HttpStatus.OK, HttpStatus.CREATED);
        grantAppIdRole(admin, "foo", "READ");

        // Connect discovery streams over mTLS with the client certificate.
        final TlsKeyPair clientKeyPair = TlsKeyPair.of(clientCert.privateKey(), clientCert.certificate());
        final ClientTlsConfig tlsConfig =
                ClientTlsConfig.builder()
                               .tlsCustomizer(b -> b.trustManager(serverCert.certificate()))
                               .build();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsProvider(TlsProvider.of(clientKeyPair), tlsConfig)
                                                  .build()) {
            final String uri = "https://127.0.0.1:" + dogma.serverAddress().getPort();
            final GrpcClientBuilder grpcBuilder = GrpcClients.builder(uri).factory(factory);

            // All resource types (CDS / LDS / RDS) are scoped to the readable group 'foo'; the 'bar' resources
            // must be filtered out. (EDS is intentionally not filtered and is covered separately.)
            // CDS and LDS are "state of the world" types: the server pushes all readable resources without the
            // client naming them, so the 'bar' resources being absent proves they are filtered out. RDS is
            // incremental: the client must name the routes it wants, so verify the readable route is served.
            final ClusterDiscoveryServiceStub cds = grpcBuilder.build(ClusterDiscoveryServiceStub.class);
            final BlockingQueue<DiscoveryResponse> cdsQueue = new ArrayBlockingQueue<>(4);
            final StreamObserver<DiscoveryRequest> cdsObserver = cds.streamClusters(responseRecorder(cdsQueue));
            request(cdsObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of());
            awaitNames(cdsQueue, cdsObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of(),
                       Set.of("groups/foo/clusters/foo-cluster"));

            final ListenerDiscoveryServiceStub lds = grpcBuilder.build(ListenerDiscoveryServiceStub.class);
            final BlockingQueue<DiscoveryResponse> ldsQueue = new ArrayBlockingQueue<>(4);
            final StreamObserver<DiscoveryRequest> ldsObserver =
                    lds.streamListeners(responseRecorder(ldsQueue));
            request(ldsObserver, Resources.V3.LISTENER_TYPE_URL, Set.of());
            awaitNames(ldsQueue, ldsObserver, Resources.V3.LISTENER_TYPE_URL, Set.of(),
                       Set.of("groups/foo/listeners/foo-listener"));

            final RouteDiscoveryServiceStub rds = grpcBuilder.build(RouteDiscoveryServiceStub.class);
            final BlockingQueue<DiscoveryResponse> rdsQueue = new ArrayBlockingQueue<>(4);
            final StreamObserver<DiscoveryRequest> rdsObserver = rds.streamRoutes(responseRecorder(rdsQueue));
            final Set<String> requestedRoutes = Set.of("groups/foo/routes/foo-route");
            request(rdsObserver, Resources.V3.ROUTE_TYPE_URL, requestedRoutes);
            awaitNames(rdsQueue, rdsObserver, Resources.V3.ROUTE_TYPE_URL, requestedRoutes,
                       Set.of("groups/foo/routes/foo-route"));

            // A repository role change must be reflected immediately on the already-open stream.
            // Grant READ on 'bar' as well -> both clusters become visible.
            grantAppIdRole(admin, "bar", "READ");
            awaitNames(cdsQueue, cdsObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of(),
                       Set.of("groups/foo/clusters/foo-cluster", "groups/bar/clusters/bar-cluster"));

            // Revoke 'foo' -> only 'bar' remains.
            revokeAppIdRole(admin, "foo");
            awaitNames(cdsQueue, cdsObserver, Resources.V3.CLUSTER_TYPE_URL, Set.of(),
                       Set.of("groups/bar/clusters/bar-cluster"));

            cdsObserver.onCompleted();
            ldsObserver.onCompleted();
            rdsObserver.onCompleted();
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
            final Message message;
            if (Resources.V3.LISTENER_TYPE_URL.equals(typeUrl)) {
                message = Listener.parseFrom(any.getValue());
            } else if (Resources.V3.ROUTE_TYPE_URL.equals(typeUrl)) {
                message = RouteConfiguration.parseFrom(any.getValue());
            } else {
                message = Cluster.parseFrom(any.getValue());
            }
            names.add(nameOf(message));
        }
        return names;
    }

    private static String nameOf(Message message) {
        if (message instanceof Cluster) {
            return ((Cluster) message).getName();
        }
        if (message instanceof Listener) {
            return ((Listener) message).getName();
        }
        return ((RouteConfiguration) message).getName();
    }

    /**
     * Reads discovery responses (acking each one) until one whose resource names equal
     * {@code expected} arrives.
     */
    private static void awaitNames(BlockingQueue<DiscoveryResponse> queue,
                                   StreamObserver<DiscoveryRequest> requestObserver,
                                   String typeUrl, Set<String> resourceNames, Set<String> expected)
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
            if (resourceNames(response, typeUrl).equals(expected)) {
                return;
            }
        }
        throw new AssertionError("Did not receive a discovery response with resources " + expected);
    }

    private static void grantAppIdRole(WebClient admin, String group, String role) {
        final AggregatedHttpResponse response =
                admin.prepare()
                     .post("/api/v1/metadata/@xds/repos/" + group + "/roles/appIdentities")
                     .content(MediaType.JSON, "{\"id\":\"xds-app\",\"role\":\"" + role + "\"}")
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
                     .execute().aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    private static void createCluster(WebClient admin, String group, String clusterId, Cluster cluster)
            throws Exception {
        post(admin, "/api/v1/xds/groups/" + group + "/clusters", "cluster_id", clusterId, cluster);
    }

    private static void createListener(WebClient admin, String group, String listenerId, Listener listener)
            throws Exception {
        post(admin, "/api/v1/xds/groups/" + group + "/listeners", "listener_id", listenerId, listener);
    }

    private static void createRoute(WebClient admin, String group, String routeId, RouteConfiguration route)
            throws Exception {
        post(admin, "/api/v1/xds/groups/" + group + "/routes", "route_id", routeId, route);
    }

    private static void post(WebClient admin, String path, String idParam, String id, Message body)
            throws Exception {
        final AggregatedHttpResponse response =
                admin.prepare()
                     .post(path)
                     .queryParam(idParam, id)
                     .content(MediaType.parse("application/yaml"), XdsTestUtil.toYaml(body))
                     .execute().aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }
}
