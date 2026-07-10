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

import static com.linecorp.centraldogma.server.storage.project.InternalProjectConstants.INTERNAL_PROJECT_XDS;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.CLUSTERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.cluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.loadAssignment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.repository.FindOptions;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.controlplane.cache.Resources.V3;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.stub.StreamObserver;

class XdsYamlCompatibilityTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @BeforeAll
    static void setup() {
        assertThat(createGroup("foo", dogma.httpClient()).status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void controlPlaneLoadsYamlCluster() throws Exception {
        // Simulate a YAML file existing in the repo (as if it was migrated from JSON).
        final String clusterName = "groups/foo/clusters/yaml-load-cluster";
        final Cluster cluster = cluster(clusterName, 1);
        pushYamlCluster("yaml-load-cluster", cluster);

        // The control plane must serve the cluster read from the .yaml file.
        await().pollInterval(100, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> checkClusterViaDiscovery(clusterName, cluster, true));
    }

    @Test
    void updateYamlCluster() throws Exception {
        final String clusterName = "groups/foo/clusters/yaml-update-cluster";
        final Cluster cluster = cluster(clusterName, 1);
        pushYamlCluster("yaml-update-cluster", cluster);

        await().pollInterval(100, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> checkClusterViaDiscovery(clusterName, cluster, true));

        // Update via the gRPC/HTTP API — updateOrDelete must locate the .yaml file.
        final Cluster updated = cluster.toBuilder()
                                       .setConnectTimeout(Duration.newBuilder().setSeconds(2).build())
                                       .build();
        final AggregatedHttpResponse response = patchCluster("foo", "yaml-update-cluster", updated);
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");

        // The .yaml file must be updated in place; no new .json file should appear.
        final Repository repo = xdsRepo("foo");
        assertThat(repo.find(Revision.HEAD, CLUSTERS_DIRECTORY + "yaml-update-cluster.yaml",
                             FindOptions.FIND_ONE_WITHOUT_CONTENT).join()).isNotEmpty();
        assertThat(repo.find(Revision.HEAD, CLUSTERS_DIRECTORY + "yaml-update-cluster.json",
                             FindOptions.FIND_ONE_WITHOUT_CONTENT).join()).isEmpty();

        // Control plane must serve the updated cluster.
        await().pollInterval(100, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> checkClusterViaDiscovery(clusterName, updated, true));
    }

    @Test
    void deleteYamlCluster() throws Exception {
        final String clusterName = "groups/foo/clusters/yaml-delete-cluster";
        final Cluster cluster = cluster(clusterName, 1);
        pushYamlCluster("yaml-delete-cluster", cluster);

        await().pollInterval(100, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> checkClusterViaDiscovery(clusterName, cluster, true));

        // Delete via the HTTP API — updateOrDelete must locate and remove the .yaml file.
        final AggregatedHttpResponse response = deleteCluster(clusterName);
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
        assertThat(response.contentUtf8()).isEqualTo("{}");

        // The .yaml file must be gone.
        final Repository repo = xdsRepo("foo");
        assertThat(repo.find(Revision.HEAD, CLUSTERS_DIRECTORY + "yaml-delete-cluster.yaml",
                             FindOptions.FIND_ONE_WITHOUT_CONTENT).join()).isEmpty();

        // Control plane must no longer serve the deleted cluster.
        await().pollInterval(100, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> checkClusterViaDiscovery(clusterName, cluster, false));
    }

    @Test
    void endpointListIncludesYamlFiles() throws Exception {
        final String endpointName = "groups/foo/endpoints/yaml-list-endpoint";
        final ClusterLoadAssignment endpoint = loadAssignment(endpointName, "127.0.0.1", 8080);
        pushYamlEndpoint("yaml-list-endpoint", endpoint);

        // listEndpoints must include the YAML endpoint with type "YAML".
        await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            final AggregatedHttpResponse response =
                    dogma.httpClient().get("/api/v1/xds/groups/foo/endpoints").aggregate().join();
            assertThat(response.status()).isSameAs(HttpStatus.OK);
            assertThat(response.contentUtf8()).contains("/endpoints/yaml-list-endpoint.yaml");
            assertThat(response.contentUtf8()).contains("\"type\":\"YAML\"");
        });
    }

    @Test
    void endpointGetReturnsYamlContent() throws Exception {
        final String endpointName = "groups/foo/endpoints/yaml-get-endpoint";
        final ClusterLoadAssignment endpoint = loadAssignment(endpointName, "127.0.0.1", 8081);
        pushYamlEndpoint("yaml-get-endpoint", endpoint);

        // getEndpoint must find and return the content of the .yaml file.
        await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            final AggregatedHttpResponse response =
                    dogma.httpClient().get("/api/v1/xds/groups/foo/endpoints/yaml-get-endpoint")
                         .aggregate().join();
            assertThat(response.status()).isSameAs(HttpStatus.OK);
            final JsonNode body = com.linecorp.centraldogma.internal.Jackson.readTree(response.contentUtf8());
            assertThat(body.get("path").asText()).isEqualTo("/endpoints/yaml-get-endpoint.yaml");
            assertThat(body.get("type").asText()).isEqualTo("YAML");
            assertThat(response.contentUtf8()).contains("127.0.0.1");
        });
    }

    @Test
    void jsonFileStillWorksAlongsideYaml() throws Exception {
        // Push a JSON cluster via the normal API (still .json).
        final String jsonClusterName = "groups/foo/clusters/json-alongside-yaml";
        final AggregatedHttpResponse createResp = createCluster("foo", "json-alongside-yaml",
                                                                 cluster(jsonClusterName, 1));
        assertThat(createResp.status()).isSameAs(HttpStatus.OK);

        // Push a YAML cluster directly.
        final String yamlClusterName = "groups/foo/clusters/yaml-alongside-json";
        final Cluster yamlCluster = cluster(yamlClusterName, 1);
        pushYamlCluster("yaml-alongside-json", yamlCluster);

        // Both must be served by the control plane.
        final Cluster expectedJson = cluster(jsonClusterName, 1).toBuilder()
                                                                 .setRespectDnsTtl(true).build();
        await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            checkClusterViaDiscovery(jsonClusterName, expectedJson, true);
            checkClusterViaDiscovery(yamlClusterName, yamlCluster, true);
        });
    }

    // ---- helpers ----

    private static void pushYamlCluster(String clusterId, Cluster cluster) throws Exception {
        final String content = JSON_MESSAGE_MARSHALLER.writeValueAsString(cluster);
        dogma.client().forRepo(INTERNAL_PROJECT_XDS, "foo")
             .commit("Add YAML cluster: " + clusterId,
                     Change.ofYamlUpsert(CLUSTERS_DIRECTORY + clusterId + ".yaml", content))
             .push().join();
    }

    private static void pushYamlEndpoint(String endpointId, ClusterLoadAssignment endpoint)
            throws Exception {
        final String content = JSON_MESSAGE_MARSHALLER.writeValueAsString(endpoint);
        dogma.client().forRepo(INTERNAL_PROJECT_XDS, "foo")
             .commit("Add YAML endpoint: " + endpointId,
                     Change.ofYamlUpsert(ENDPOINTS_DIRECTORY + endpointId + ".yaml", content))
             .push().join();
    }

    private static AggregatedHttpResponse createCluster(String group, String clusterId, Cluster cluster)
            throws Exception {
        return dogma.httpClient()
                    .prepare()
                    .method(HttpMethod.POST)
                    .path("/api/v1/xds/groups/" + group + "/clusters")
                    .queryParam("cluster_id", clusterId)
                    .header(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                    .content(MediaType.JSON_UTF_8, JSON_MESSAGE_MARSHALLER.writeValueAsString(cluster))
                    .execute().aggregate().join();
    }

    private static AggregatedHttpResponse patchCluster(String group, String clusterId, Cluster cluster)
            throws Exception {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.PATCH,
                                       "/api/v1/xds/groups/" + group + "/clusters/" + clusterId)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return dogma.httpClient()
                    .execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(cluster))
                    .aggregate().join();
    }

    private static AggregatedHttpResponse deleteCluster(String clusterName) {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.DELETE, "/api/v1/xds/" + clusterName)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous").build();
        return dogma.httpClient().execute(headers).aggregate().join();
    }

    private static Repository xdsRepo(String group) {
        return dogma.projectManager().get(INTERNAL_PROJECT_XDS).repos().get(group);
    }

    private static void checkClusterViaDiscovery(String clusterName, Cluster expectedCluster,
                                                  boolean shouldExist)
            throws InterruptedException, InvalidProtocolBufferException {
        final ClusterDiscoveryServiceStub client =
                GrpcClients.newClient(dogma.httpClient().uri(), ClusterDiscoveryServiceStub.class);
        final BlockingQueue<DiscoveryResponse> queue = new ArrayBlockingQueue<>(2);
        final StreamObserver<DiscoveryRequest> req = client.streamClusters(new StreamObserver<>() {

            @Override
            public void onNext(DiscoveryResponse value) {
                queue.add(value);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {}
        });
        req.onNext(DiscoveryRequest.newBuilder().setTypeUrl(V3.CLUSTER_TYPE_URL)
                                   .addResourceNames(clusterName).build());
        if (shouldExist) {
            final DiscoveryResponse resp = queue.take();
            final List<Any> resources = resp.getResourcesList();
            assertThat(resources).hasSize(1);
            assertThat(Cluster.parseFrom(resources.get(0).getValue())).isEqualTo(expectedCluster);
        } else {
            final DiscoveryResponse resp = queue.poll(300, TimeUnit.MILLISECONDS);
            assertThat(resp).isNull();
        }
    }
}
