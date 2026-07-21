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

package com.linecorp.centraldogma.xds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import org.apache.shiro.config.Ini;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.auth.shiro.ShiroAuthProviderFactory;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddressBuilder;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import io.fabric8.mockwebserver.ServerRequest;
import io.fabric8.mockwebserver.ServerResponse;
import io.fabric8.mockwebserver.http.MockResponse;
import io.fabric8.mockwebserver.http.RecordedRequest;

/**
 * Starts a standalone Central Dogma server with the xDS plugin and the bundled web application, secured with
 * Apache Shiro authentication and pre-populated with sample xDS resources, for manually testing the xDS web
 * UI served under {@code /app/xds}.
 *
 * <p>Run it with {@code ./gradlew :xds:runXdsTestServer} and open
 * <a href="http://127.0.0.1:36462/app/xds">http://127.0.0.1:36462/app/xds</a>. For live UI development, run
 * {@code npm run develop} in the {@code webapp} directory and use {@code http://127.0.0.1:3000/app/xds}
 * instead, which talks to this backend on port {@value #PORT}.
 *
 * <p>It also starts an in-process Kubernetes mock server with a sample {@code NodePort} service so that k8s
 * endpoint aggregators can be exercised without a real cluster. The mock's control plane URL, namespace and
 * service name are printed on startup; create a K8s Aggregator with those values to see the generated EDS
 * under the group's Endpoints.
 */
public final class XdsTestServer {

    private static final int PORT = 36462;

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin";

    private static final String USER_USERNAME = "user1";
    private static final String USER_PASSWORD = "user1";

    private static final String SAMPLE_K8S_SERVICE = "nginx-service";
    // Sample xDS data created on startup so a k8s endpoint aggregator (using a credential) is ready to inspect.
    private static final String SAMPLE_GROUP = "my-group";
    private static final String SAMPLE_CREDENTIAL = "k8s-credential";
    private static final String SAMPLE_AGGREGATOR = "nginx";
    // A non-admin application identity with READ access to SAMPLE_GROUP, so the per application-identity
    // snapshot view (and a token-authenticated discovery client) can be demonstrated out of the box.
    private static final String SAMPLE_APP_ID = "xds-app";

    // A sample LDS -> RDS -> CDS -> EDS graph so the "References" links between resources can be exercised in
    // the web UI. Several resources reference more than one child (a listener with two route configs, routes
    // that fan out to multiple clusters via weighted clusters) so multiple links per resource are shown.
    private static final String SAMPLE_LISTENER = "my-listener";
    private static final String SAMPLE_TCP_LISTENER = "my-tcp-listener";
    private static final String SAMPLE_ROUTE = "my-route";
    private static final String SAMPLE_ROUTE_2 = "my-route-2";
    private static final String SAMPLE_CLUSTER = "my-cluster";
    private static final String SAMPLE_CLUSTER_2 = "my-cluster-2";
    private static final String SAMPLE_ENDPOINT = "my-endpoint";
    private static final String SAMPLE_ENDPOINT_2 = "my-endpoint-2";

    @SuppressWarnings("UncommentedMain")
    public static void main(String[] args) throws IOException {
        final Path rootDir = Files.createTempDirectory("dogma-xds-test");
        final CentralDogmaBuilder builder = new CentralDogmaBuilder(rootDir.toFile());
        builder.port(PORT, SessionProtocol.HTTP);
        // Allow the web app's Next.js dev server (webapp `npm run develop`, port 3000) to call the APIs
        // cross-origin.
        builder.cors("http://127.0.0.1:36462", "http://localhost:36462",
                     "https://127.0.0.1:36462", "https://localhost:36462",
                     "http://127.0.0.1:3000", "http://localhost:3000");
        builder.systemAdministrators(ADMIN_USERNAME)
               .authProviderFactory(new ShiroAuthProviderFactory(unused -> {
                   final Ini iniConfig = new Ini();
                   final Ini.Section users = iniConfig.addSection("users");
                   users.put(ADMIN_USERNAME, ADMIN_PASSWORD);
                   users.put(USER_USERNAME, USER_PASSWORD);
                   return iniConfig;
               }));
        final CentralDogma server = builder.build();
        server.start().join();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));

        final NamespacedKubernetesClient k8sClient = startKubernetesMock();

        final String sampleAppToken = bootstrapSampleData(PORT, k8sClient);

        System.out.println();
        System.out.println("==========================================================================");
        System.out.println(" Central Dogma xDS test server is running.");
        System.out.println();
        System.out.println("   Web UI : http://127.0.0.1:" + PORT + "/app/xds");
        System.out.println("            (or `npm run develop` in 'webapp' -> http://127.0.0.1:3000/app/xds)");
        System.out.println("   Admin  : " + ADMIN_USERNAME + " / " + ADMIN_PASSWORD + " (system admin)");
        System.out.println("   User   : " + USER_USERNAME + " / " + USER_PASSWORD + " (regular user)");
        System.out.println();
        System.out.println(" Kubernetes mock (for testing k8s endpoint aggregators):");
        System.out.println("   Control plane URL : " + k8sClient.getMasterUrl());
        System.out.println("   Namespace         : " + k8sClient.getNamespace());
        System.out.println("   Service name      : " + SAMPLE_K8S_SERVICE + " (NodePort 30000)");
        System.out.println();
        System.out.println(" Pre-created sample (ready to inspect in the web UI):");
        System.out.println("   Group       : " + SAMPLE_GROUP);
        System.out.println("   Credential  : " + SAMPLE_CREDENTIAL + " (access token)");
        System.out.println("   Aggregator  : " + SAMPLE_AGGREGATOR + " -> generated EDS under " +
                           SAMPLE_GROUP + "'s Endpoints (read-only)");
        System.out.println("   References  : 2 listeners (LDS) -> 2 routes (RDS) -> 2 clusters (CDS) -> " +
                           "2 endpoints (EDS); some resources reference multiple children (e.g. " +
                           SAMPLE_LISTENER + " -> 2 routes, " + SAMPLE_ROUTE + " -> 2 clusters)");
        if (sampleAppToken != null) {
            System.out.println();
            System.out.println(" Sample application identity (for the per app-id 'xDS Control Plane' view):");
            System.out.println("   App id    : " + SAMPLE_APP_ID + " (READ on " + SAMPLE_GROUP + ')');
            System.out.println("   App token : " + sampleAppToken);
            System.out.println("   Connect a token-authenticated ADS client as this identity with:");
            System.out.println("     ./gradlew :xds:runXdsTestClient --args=\"http://127.0.0.1:" + PORT +
                               ' ' + SAMPLE_APP_ID + "-node default " + sampleAppToken + '"');
            System.out.println("   Then open Settings -> xDS Control Plane -> Snapshot, scope 'By application" +
                               " identity', and pick " + SAMPLE_APP_ID + '.');
        }
        System.out.println();
        System.out.println(" Press Ctrl+C to stop.");
        System.out.println("==========================================================================");
        System.out.println();
    }

    /**
     * Creates a sample group, an access-token credential and a Kubernetes endpoint aggregator that references
     * the credential and watches the mock's {@value #SAMPLE_K8S_SERVICE} service, plus a non-admin application
     * identity with READ access to the group. Returns that identity's access token (or {@code null} on
     * failure) so a token-authenticated client can be run against it. Best-effort: failures are logged but do
     * not stop the server.
     */
    private static String bootstrapSampleData(int port, NamespacedKubernetesClient k8sClient) {
        final String baseUri = "http://127.0.0.1:" + port;
        String appToken = null;
        try {
            final String adminToken = TestAuthMessageUtil.getAccessToken(
                    WebClient.of(baseUri), ADMIN_USERNAME, ADMIN_PASSWORD, "xds-bootstrap", true);
            final WebClient admin = WebClient.builder(baseUri).auth(AuthToken.ofOAuth2(adminToken)).build();

            bootstrapStep("create group", admin.prepare()
                    .post("/api/v1/xds/groups")
                    .queryParam("group_id", SAMPLE_GROUP)
                    .execute().aggregate().join());

            // An access-token credential the aggregator authenticates the Kubernetes API with. The mock does
            // not validate it, but this exercises the credential resolution path.
            bootstrapStep("create credential", admin.prepare()
                    .post("/api/v1/projects/@xds/repos/" + SAMPLE_GROUP + "/credentials")
                    .content(MediaType.JSON,
                             "{\"credentialId\":\"" + SAMPLE_CREDENTIAL + "\",\"credential\":" +
                             "{\"type\":\"ACCESS_TOKEN\",\"name\":\"\",\"accessToken\":\"sample-token\"}}")
                    .execute().aggregate().join());

            bootstrapStep("create k8s aggregator", admin.prepare()
                    .post("/api/v1/xds/groups/" + SAMPLE_GROUP + "/k8s/endpointAggregators")
                    .queryParam("aggregator_id", SAMPLE_AGGREGATOR)
                    .content(MediaType.parse("application/yaml"),
                             "localityLbEndpoints:\n" +
                             "  - watcher:\n" +
                             "      serviceName: " + SAMPLE_K8S_SERVICE + '\n' +
                             "      kubeconfig:\n" +
                             "        controlPlaneUrl: '" + k8sClient.getMasterUrl() + "'\n" +
                             "        namespace: '" + k8sClient.getNamespace() + "'\n" +
                             "        credentialId: " + SAMPLE_CREDENTIAL + '\n')
                    .execute().aggregate().join());

            bootstrapSampleResources(admin);

            // A non-admin application identity, granted READ on the sample group. A discovery client
            // authenticating with this token is served a snapshot scoped to the groups it can read, which is
            // what the per application-identity snapshot view shows.
            appToken = TestAuthMessageUtil.getAccessToken(
                    WebClient.of(baseUri), ADMIN_USERNAME, ADMIN_PASSWORD, SAMPLE_APP_ID, false);
            bootstrapStep("grant " + SAMPLE_APP_ID + " READ on " + SAMPLE_GROUP, admin.prepare()
                    .post("/api/v1/metadata/@xds/repos/" + SAMPLE_GROUP + "/roles/appIdentities")
                    .content(MediaType.JSON, "{\"id\":\"" + SAMPLE_APP_ID + "\",\"role\":\"READ\"}")
                    .execute().aggregate().join());
        } catch (Throwable t) {
            System.err.println("Failed to bootstrap sample xDS data: " + t);
        }
        return appToken;
    }

    /**
     * Creates a connected LDS -> RDS -> CDS -> EDS graph so the resource "References" links can be exercised in
     * the web UI.
     */
    private static void bootstrapSampleResources(WebClient admin) {
        // EDS: two ClusterLoadAssignments, each with a single static endpoint. The server overrides the cluster
        // name to 'groups/my-group/clusters/{endpointId}'.
        bootstrapResource(admin, "endpoints", "endpoint_id", SAMPLE_ENDPOINT, endpointYaml(8080));
        bootstrapResource(admin, "endpoints", "endpoint_id", SAMPLE_ENDPOINT_2, endpointYaml(8081));

        // CDS: two EDS-type clusters, each referencing one of the EDS resources above through its service name.
        bootstrapResource(admin, "clusters", "cluster_id", SAMPLE_CLUSTER,
                          clusterYaml(SAMPLE_CLUSTER, SAMPLE_ENDPOINT));
        bootstrapResource(admin, "clusters", "cluster_id", SAMPLE_CLUSTER_2,
                          clusterYaml(SAMPLE_CLUSTER_2, SAMPLE_ENDPOINT_2));

        // RDS: a route configuration that fans out to BOTH clusters - one route targets a single cluster, the
        // other splits traffic across the two clusters with weighted clusters, so the route references two CDS.
        bootstrapResource(admin, "routes", "route_id", SAMPLE_ROUTE,
                          "name: my-route\n" +
                          "virtualHosts:\n" +
                          "  - name: my-vhost\n" +
                          "    domains:\n" +
                          "      - '*'\n" +
                          "    routes:\n" +
                          "      - match:\n" +
                          "          prefix: /a\n" +
                          "        route:\n" +
                          "          cluster: 'groups/" + SAMPLE_GROUP + "/clusters/my-cluster'\n" +
                          "      - match:\n" +
                          "          prefix: /b\n" +
                          "        route:\n" +
                          "          weightedClusters:\n" +
                          "            clusters:\n" +
                          "              - name: 'groups/" + SAMPLE_GROUP + "/clusters/my-cluster'\n" +
                          "                weight: 50\n" +
                          "              - name: 'groups/" + SAMPLE_GROUP + "/clusters/my-cluster-2'\n" +
                          "                weight: 50\n");

        // RDS: a second, simple route configuration targeting the other cluster.
        bootstrapResource(admin, "routes", "route_id", SAMPLE_ROUTE_2,
                          "name: my-route-2\n" +
                          "virtualHosts:\n" +
                          "  - name: my-vhost\n" +
                          "    domains:\n" +
                          "      - '*'\n" +
                          "    routes:\n" +
                          "      - match:\n" +
                          "          prefix: /\n" +
                          "        route:\n" +
                          "          cluster: 'groups/" + SAMPLE_GROUP + "/clusters/my-cluster-2'\n");

        // LDS: an HTTP listener with two filter chains whose connection managers point at BOTH route configs
        // via RDS, so the listener references two RDS.
        bootstrapResource(admin, "listeners", "listener_id", SAMPLE_LISTENER,
                          "name: my-listener\n" +
                          "address:\n" +
                          "  socketAddress:\n" +
                          "    address: 0.0.0.0\n" +
                          "    portValue: 10000\n" +
                          "filterChains:\n" +
                          hcmFilterChainYaml("chain-a", "a.example.com", SAMPLE_ROUTE) +
                          hcmFilterChainYaml("chain-b", "b.example.com", SAMPLE_ROUTE_2));

        // LDS: a TCP listener that splits traffic across BOTH clusters directly with weighted clusters, so the
        // listener references two CDS.
        bootstrapResource(admin, "listeners", "listener_id", SAMPLE_TCP_LISTENER,
                          "name: my-tcp-listener\n" +
                          "address:\n" +
                          "  socketAddress:\n" +
                          "    address: 0.0.0.0\n" +
                          "    portValue: 10001\n" +
                          "filterChains:\n" +
                          "  - filters:\n" +
                          "      - name: envoy.filters.network.tcp_proxy\n" +
                          "        typedConfig:\n" +
                          "          '@type': 'type.googleapis.com/" +
                          "envoy.extensions.filters.network.tcp_proxy.v3.TcpProxy'\n" +
                          "          statPrefix: tcp\n" +
                          "          weightedClusters:\n" +
                          "            clusters:\n" +
                          "              - name: 'groups/" + SAMPLE_GROUP + "/clusters/my-cluster'\n" +
                          "                weight: 50\n" +
                          "              - name: 'groups/" + SAMPLE_GROUP + "/clusters/my-cluster-2'\n" +
                          "                weight: 50\n");
    }

    // An EDS ClusterLoadAssignment with a single static endpoint at 127.0.0.1:{port}.
    private static String endpointYaml(int port) {
        return "endpoints:\n" +
               "  - lbEndpoints:\n" +
               "      - endpoint:\n" +
               "          address:\n" +
               "            socketAddress:\n" +
               "              address: 127.0.0.1\n" +
               "              portValue: " + port + '\n' +
               "        healthStatus: HEALTHY\n" +
               "        loadBalancingWeight: 1000\n";
    }

    // An EDS-type cluster that references the given endpoint via its EDS service name (the cluster name the
    // server assigns to the endpoint, which uses '/clusters/').
    private static String clusterYaml(String clusterId, String endpointId) {
        return "name: " + clusterId + '\n' +
               "type: EDS\n" +
               "connectTimeout: 5s\n" +
               "edsClusterConfig:\n" +
               "  edsConfig:\n" +
               "    ads: {}\n" +
               "    resourceApiVersion: V3\n" +
               "  serviceName: 'groups/" + SAMPLE_GROUP + "/clusters/" + endpointId + "'\n";
    }

    // A listener filter chain with an HTTP connection manager that references the given route config via RDS.
    // Returns a YAML sequence item (starts with '  - ') for embedding directly under 'filterChains:'.
    private static String hcmFilterChainYaml(String chainName, String serverName, String routeId) {
        return "  - name: " + chainName + '\n' +
               "    filterChainMatch:\n" +
               "      serverNames:\n" +
               "        - " + serverName + '\n' +
               "    filters:\n" +
               "      - name: envoy.filters.network.http_connection_manager\n" +
               "        typedConfig:\n" +
               "          '@type': 'type.googleapis.com/" +
               "envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager'\n" +
               "          statPrefix: ingress_http\n" +
               "          rds:\n" +
               "            configSource:\n" +
               "              ads: {}\n" +
               "              resourceApiVersion: V3\n" +
               "            routeConfigName: 'groups/" + SAMPLE_GROUP + "/routes/" + routeId + "'\n" +
               "          httpFilters:\n" +
               "            - name: envoy.filters.http.router\n" +
               "              typedConfig:\n" +
               "                '@type': 'type.googleapis.com/" +
               "envoy.extensions.filters.http.router.v3.Router'\n";
    }

    private static void bootstrapResource(WebClient admin, String type,
                                          String idParam, String id, String yaml) {
        bootstrapStep("create " + type + '/' + id, admin.prepare()
                .post("/api/v1/xds/groups/" + SAMPLE_GROUP + '/' + type)
                .queryParam(idParam, id)
                .content(MediaType.parse("application/yaml"), yaml)
                .execute().aggregate().join());
    }

    private static void bootstrapStep(String what, AggregatedHttpResponse response) {
        final int code = response.status().code();
        // 409 Conflict means the resource already exists (e.g. 'my-group' is created on startup by
        // CreatingInternalGroupPlugin, or the server data was reused) — that is fine for bootstrapping.
        if (response.status().isSuccess() || code == 409) {
            System.out.println(
                    "   bootstrap: " + what + " -> " + code + (code == 409 ? " (already exists)" : ""));
        } else {
            System.err.println("   bootstrap: " + what + " FAILED -> " + response.status() + ' ' +
                               response.contentUtf8());
        }
    }

    /**
     * Starts an in-process fabric8 Kubernetes mock server (CRUD-enabled, plain HTTP) populated with two nodes,
     * two pods and a {@code NodePort} service ({@value #SAMPLE_K8S_SERVICE}). A k8s endpoint aggregator
     * pointed at the returned client's master URL / namespace / service resolves to the node IPs and the
     * service node port, exactly like the {@code XdsKubernetesServiceTest} setup.
     */
    private static NamespacedKubernetesClient startKubernetesMock() {
        final Map<ServerRequest, Queue<ServerResponse>> responses = new HashMap<>();
        final KubernetesMockServer mock = new KubernetesMockServer(
                new Context(), new MockWebServer(), responses, new OptionsTolerantDispatcher(responses), false);
        mock.init();
        Runtime.getRuntime().addShutdownHook(new Thread(mock::destroy));
        final NamespacedKubernetesClient client = mock.createClient();

        final Map<String, String> selector = ImmutableMap.of("app", "nginx");
        client.nodes().resource(newNode("1.1.1.1")).create();
        client.nodes().resource(newNode("2.2.2.2")).create();
        client.pods().resource(newPod("node-1.1.1.1", selector)).create();
        client.pods().resource(newPod("node-2.2.2.2", selector)).create();
        client.services().resource(newService(SAMPLE_K8S_SERVICE, selector)).create();
        return client;
    }

    private static Node newNode(String ip) {
        return new NodeBuilder()
                .withMetadata(new ObjectMetaBuilder().withName("node-" + ip).build())
                .withStatus(new NodeStatusBuilder()
                                    .withAddresses(new NodeAddressBuilder()
                                                           .withType("InternalIP").withAddress(ip).build())
                                    .build())
                .build();
    }

    private static Pod newPod(String nodeName, Map<String, String> labels) {
        return new PodBuilder()
                .withMetadata(new ObjectMetaBuilder().withName("nginx-pod-" + nodeName)
                                                     .withLabels(labels).build())
                .withSpec(new PodSpecBuilder()
                                  .withNodeName(nodeName)
                                  .withContainers(new ContainerBuilder()
                                                          .withName("nginx").withImage("nginx:1.14.2").build())
                                  .build())
                .build();
    }

    private static Service newService(String name, Map<String, String> selector) {
        return new ServiceBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(name).build())
                .withSpec(new ServiceSpecBuilder()
                                  .withType("NodePort")
                                  .withSelector(selector)
                                  .withPorts(new ServicePortBuilder().withPort(80).withNodePort(30000).build())
                                  .build())
                .build();
    }

    // The mock web server does not handle CORS pre-flight OPTIONS requests, so answer them with 200 and
    // delegate everything else to the CRUD dispatcher.
    private static final class OptionsTolerantDispatcher extends KubernetesMixedDispatcher {
        OptionsTolerantDispatcher(Map<ServerRequest, Queue<ServerResponse>> responses) {
            super(responses);
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            if ("OPTIONS".equals(request.getMethod())) {
                return new MockResponse().setResponseCode(200);
            }
            return super.dispatch(request);
        }
    }

    private XdsTestServer() {}
}
