/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.xds.k8s.v1;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.K8S_ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService.K8S_ENDPOINT_AGGREGATORS_DIRECTORY;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceTest.assertAggregator;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceTest.assertOk;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceTest.createAggregator;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceTest.newDeployment;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceTest.newNode;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceTest.newPod;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceTest.newService;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceTest.updateAggregator;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.UInt32Value;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.core.v3.Locality;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

@EnableKubernetesMockClient(crud = true)
class AggregatingMultipleKubernetesTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    static KubernetesClient client;

    @BeforeAll
    static void setup() {
        final AggregatedHttpResponse response = createGroup("foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        // Prepare Kubernetes resources
        final List<Node> nodes = ImmutableList.of(newNode("1.1.1.1"), newNode("2.2.2.2"));
        final Map<String, String> selector = ImmutableMap.of("app1", "nginx1");
        final Deployment deployment = newDeployment("deployment1", selector);
        final Service service = newService("service1", selector);
        createResources(nodes, deployment, service);

        final List<Node> nodes2 = ImmutableList.of(newNode("3.3.3.3"), newNode("4.4.4.4"));
        final Map<String, String> selector2 = ImmutableMap.of("app2", "nginx2");
        final Deployment deployment2 = newDeployment("deployment2", selector2);
        final Service service2 = newService("service2", selector2);
        createResources(nodes2, deployment2, service2);
    }

    private static void createResources(List<Node> nodes, Deployment deployment, Service service) {
        final List<Pod> pods = nodes.stream()
                                    .map(node -> node.getMetadata().getName())
                                    .map(nodeName -> newPod(deployment.getSpec().getTemplate(), nodeName))
                                    .collect(toImmutableList());

        // Create Kubernetes resources
        for (Node node : nodes) {
            client.nodes().resource(node).create();
        }
        client.pods().resource(pods.get(0)).create();
        client.pods().resource(pods.get(1)).create();
        client.apps().deployments().resource(deployment).create();
        client.services().resource(service).create();
    }

    @Test
    void aggregateMultipleKubernetes() throws IOException {
        final String aggregatorId = "foo-k8s-cluster/1";
        final String clusterName = "groups/foo/k8s/clusters/" + aggregatorId;
        final KubernetesEndpointAggregator aggregator = aggregator(aggregatorId);
        final AggregatedHttpResponse response = createAggregator(aggregator, aggregatorId, dogma.httpClient());
        assertOk(response);
        final String json = response.contentUtf8();
        final KubernetesEndpointAggregator expectedAggregator =
                aggregator.toBuilder().setClusterName(clusterName) // cluster name is set by the service.
                          .build();
        assertAggregator(json, expectedAggregator);

        final Repository fooGroup = dogma.projectManager().get(XDS_CENTRAL_DOGMA_PROJECT).repos().get("foo");
        final Entry<JsonNode> aggregatorEntry =
                fooGroup.get(Revision.HEAD, Query.ofJson(
                        K8S_ENDPOINT_AGGREGATORS_DIRECTORY + aggregatorId + ".json")).join();
        assertAggregator(aggregatorEntry.contentAsText(), expectedAggregator);
        System.err.println("aggregatorEntry.revision(): " + aggregatorEntry.revision());

        await().until(() -> fooGroup.normalizeNow(Revision.HEAD).equals(aggregatorEntry.revision().forward(1)));

        final Entry<JsonNode> endpointEntry = fooGroup.getOrNull(Revision.HEAD, Query.ofJson(
                K8S_ENDPOINTS_DIRECTORY + aggregatorId + ".json")).join();
        System.err.println("endpointEntry.revision(): " + endpointEntry.revision());
        assertThatJson(endpointEntry.content()).isEqualTo(
                '{' +
                "  \"clusterName\": \"groups/foo/k8s/clusters/foo-k8s-cluster/1\"," +
                "  \"endpoints\": [ {" +
                "    \"locality\": {" +
                "      \"zone\": \"zone1\"" +
                "    }," +
                "    \"lbEndpoints\": [ {" +
                "      \"endpoint\": {" +
                "        \"address\": {" +
                "          \"socketAddress\": {" +
                "            \"address\": \"1.1.1.1\"," +
                "            \"portValue\": 30000" +
                "          }" +
                "        }" +
                "      }" +
                "    }, {" +
                "      \"endpoint\": {" +
                "        \"address\": {" +
                "          \"socketAddress\": {" +
                "            \"address\": \"2.2.2.2\"," +
                "            \"portValue\": 30000" +
                "          }" +
                "        }" +
                "      }" +
                "    } ]" +
                "  }, {" +
                "    \"locality\": {" +
                "      \"zone\": \"zone2\"" +
                "    }," +
                "    \"lbEndpoints\": [ {" +
                "      \"endpoint\": {" +
                "        \"address\": {" +
                "          \"socketAddress\": {" +
                "            \"address\": \"3.3.3.3\"," +
                "            \"portValue\": 30000" +
                "          }" +
                "        }" +
                "      }" +
                "    }, {" +
                "      \"endpoint\": {" +
                "        \"address\": {" +
                "          \"socketAddress\": {" +
                "            \"address\": \"4.4.4.4\"," +
                "            \"portValue\": 30000" +
                "          }" +
                "        }" +
                "      }" +
                "    } ]," +
                "    \"loadBalancingWeight\": 10," +
                "    \"priority\": 1" +
                "  } ]" +
                '}'
        );
        // Remove service2
        final KubernetesEndpointAggregator aggregator1 =
                aggregator.toBuilder().removeLocalityLbEndpoints(1).build();
        final AggregatedHttpResponse response1 =
                updateAggregator(aggregator1, aggregatorId, dogma.httpClient());
        assertOk(response1);
        await().until(() -> fooGroup.normalizeNow(Revision.HEAD).equals(
                endpointEntry.revision().forward(2))); // 2 because of the aggregator update and endpoint update
        final Entry<JsonNode> endpointEntry1 = fooGroup.getOrNull(Revision.HEAD, Query.ofJson(
                K8S_ENDPOINTS_DIRECTORY + aggregatorId + ".json")).join();
        System.err.println("endpointEntry.revision(): " + endpointEntry1.revision());
        assertThatJson(endpointEntry1.content()).isEqualTo(
                '{' +
                "  \"clusterName\": \"groups/foo/k8s/clusters/foo-k8s-cluster/1\"," +
                "  \"endpoints\": [ {" +
                "    \"locality\": {" +
                "      \"zone\": \"zone1\"" +
                "    }," +
                "    \"lbEndpoints\": [ {" +
                "      \"endpoint\": {" +
                "        \"address\": {" +
                "          \"socketAddress\": {" +
                "            \"address\": \"1.1.1.1\"," +
                "            \"portValue\": 30000" +
                "          }" +
                "        }" +
                "      }" +
                "    }, {" +
                "      \"endpoint\": {" +
                "        \"address\": {" +
                "          \"socketAddress\": {" +
                "            \"address\": \"2.2.2.2\"," +
                "            \"portValue\": 30000" +
                "          }" +
                "        }" +
                "      }" +
                "    } ]" +
                "  }]" +
                '}'
        );
    }

    private static KubernetesEndpointAggregator aggregator(String aggregatorId) {
        final Kubeconfig kubeconfig = Kubeconfig.newBuilder()
                                                .setControlPlaneUrl(client.getMasterUrl().toString())
                                                .setNamespace(client.getNamespace())
                                                .setTrustCerts(true)
                                                .build();
        final ServiceEndpointWatcher watcher = ServiceEndpointWatcher.newBuilder()
                                                                     .setServiceName("service1")
                                                                     .setKubeconfig(kubeconfig)
                                                                     .build();
        final ServiceEndpointWatcher watcher2 = ServiceEndpointWatcher.newBuilder()
                                                                      .setServiceName("service2")
                                                                      .setKubeconfig(kubeconfig)
                                                                      .build();
        return KubernetesEndpointAggregator
                .newBuilder()
                .setName("groups/foo/k8s/endpointAggregators/" + aggregatorId)
                .addLocalityLbEndpoints(KubernetesLocalityLbEndpoints.newBuilder()
                                                                     .setWatcher(watcher)
                                                                     .setLocality(Locality.newBuilder()
                                                                                          .setZone("zone1")
                                                                                          .build())
                                                                     .build())
                .addLocalityLbEndpoints(KubernetesLocalityLbEndpoints.newBuilder()
                                                                     .setWatcher(watcher2)
                                                                     .setLocality(Locality.newBuilder()
                                                                                          .setZone("zone2")
                                                                                          .build())
                                                                     .setLoadBalancingWeight(UInt32Value.of(10))
                                                                     .setPriority(1)
                                                                     .build())
                .build();
    }
}
