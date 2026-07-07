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
package com.linecorp.centraldogma.xds.k8s.v1;

import static com.linecorp.centraldogma.xds.endpoint.v1.XdsEndpointServiceTest.checkEndpointsViaDiscoveryRequest;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.endpoint;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceTest.assertOk;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceTest.createAggregator;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

@EnableKubernetesMockClient(crud = true)
class KubernetesEndpointMetadataTest {

    private static final String ZONE_KEY = "topology.kubernetes.io/zone";
    private static final String REGION_KEY = "topology.kubernetes.io/region";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    static KubernetesClient client;

    @BeforeAll
    static void setup() {
        final AggregatedHttpResponse response = createGroup("foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        // Two nodes, each with topology labels and a rack annotation.
        client.nodes().resource(newNode("node-a", "1.1.1.1",
                                        ImmutableMap.of(ZONE_KEY, "zone-a", REGION_KEY, "region-1",
                                                        "kubernetes.io/arch", "amd64"),
                                        ImmutableMap.of("rack", "rack-1"))).create();
        client.nodes().resource(newNode("node-b", "2.2.2.2",
                                        ImmutableMap.of(ZONE_KEY, "zone-b", REGION_KEY, "region-1",
                                                        "kubernetes.io/arch", "amd64"),
                                        ImmutableMap.of("rack", "rack-2"))).create();

        // A NodePort service backed by one Pod per node.
        client.services().resource(newService("meta-service", ImmutableMap.of("app", "meta"), 30000)).create();
        client.pods().resource(newPod("meta-pod-a", "node-a",
                                      ImmutableMap.of("app", "meta", "version", "v1"))).create();
        client.pods().resource(newPod("meta-pod-b", "node-b",
                                      ImmutableMap.of("app", "meta", "version", "v2"))).create();

        // A NodePort service backed by two Pods on the same node, producing duplicate endpoints.
        client.services().resource(newService("dup-service", ImmutableMap.of("app", "dup"), 31000)).create();
        client.pods().resource(newPod("dup-pod-1", "node-a", ImmutableMap.of("app", "dup"))).create();
        client.pods().resource(newPod("dup-pod-2", "node-a", ImmutableMap.of("app", "dup"))).create();
    }

    @Test
    void copyNodeLabelWithExactKey() throws IOException {
        final String aggregatorId = "meta-node-label";
        final ServiceEndpointWatcher watcher =
                watcher("meta-service")
                        .addMetadataMapping(MetadataMapping.newBuilder()
                                                           .setResourceType(MetadataMapping.ResourceType.NODE)
                                                           .setEntryType(MetadataMapping.EntryType.LABEL)
                                                           .setSourceKey(ZONE_KEY)
                                                           .setMetadataKey("zone"))
                        .build();
        final String clusterName = createAndGetClusterName(aggregatorId, watcher);
        checkEndpointsViaDiscoveryRequest(
                dogma.httpClient().uri(),
                clusterLoadAssignment(clusterName,
                                      lbEndpoint("1.1.1.1", 30000, "envoy.lb",
                                                 ImmutableMap.of("zone", "zone-a")),
                                      lbEndpoint("2.2.2.2", 30000, "envoy.lb",
                                                 ImmutableMap.of("zone", "zone-b"))),
                clusterName);
    }

    @Test
    void copyNodeLabelsWithPrefix() throws IOException {
        final String aggregatorId = "meta-node-prefix";
        final ServiceEndpointWatcher watcher =
                watcher("meta-service")
                        .addMetadataMapping(MetadataMapping.newBuilder()
                                                           .setResourceType(MetadataMapping.ResourceType.NODE)
                                                           .setEntryType(MetadataMapping.EntryType.LABEL)
                                                           .setSourceKeyPrefix("topology.kubernetes.io/"))
                        .build();
        final String clusterName = createAndGetClusterName(aggregatorId, watcher);
        // Only the keys under the prefix are copied, and their original keys are preserved.
        checkEndpointsViaDiscoveryRequest(
                dogma.httpClient().uri(),
                clusterLoadAssignment(clusterName,
                                      lbEndpoint("1.1.1.1", 30000, "envoy.lb",
                                                 ImmutableMap.of(ZONE_KEY, "zone-a", REGION_KEY, "region-1")),
                                      lbEndpoint("2.2.2.2", 30000, "envoy.lb",
                                                 ImmutableMap.of(ZONE_KEY, "zone-b", REGION_KEY, "region-1"))),
                clusterName);
    }

    @Test
    void copyPodLabelWithCustomNamespace() throws IOException {
        final String aggregatorId = "meta-pod-label";
        final ServiceEndpointWatcher watcher =
                watcher("meta-service")
                        .addMetadataMapping(MetadataMapping.newBuilder()
                                                           .setResourceType(MetadataMapping.ResourceType.POD)
                                                           .setEntryType(MetadataMapping.EntryType.LABEL)
                                                           .setSourceKey("version")
                                                           .setMetadataNamespace("com.example.custom")
                                                           .setMetadataKey("ver"))
                        .build();
        final String clusterName = createAndGetClusterName(aggregatorId, watcher);
        checkEndpointsViaDiscoveryRequest(
                dogma.httpClient().uri(),
                clusterLoadAssignment(clusterName,
                                      lbEndpoint("1.1.1.1", 30000, "com.example.custom",
                                                 ImmutableMap.of("ver", "v1")),
                                      lbEndpoint("2.2.2.2", 30000, "com.example.custom",
                                                 ImmutableMap.of("ver", "v2"))),
                clusterName);
    }

    @Test
    void copyNodeAnnotation() throws IOException {
        final String aggregatorId = "meta-node-annotation";
        final ServiceEndpointWatcher watcher =
                watcher("meta-service")
                        .addMetadataMapping(MetadataMapping.newBuilder()
                                                           .setResourceType(MetadataMapping.ResourceType.NODE)
                                                           .setEntryType(MetadataMapping.EntryType.ANNOTATION)
                                                           .setSourceKey("rack")) // metadata_key defaults to it
                        .build();
        final String clusterName = createAndGetClusterName(aggregatorId, watcher);
        checkEndpointsViaDiscoveryRequest(
                dogma.httpClient().uri(),
                clusterLoadAssignment(clusterName,
                                      lbEndpoint("1.1.1.1", 30000, "envoy.lb",
                                                 ImmutableMap.of("rack", "rack-1")),
                                      lbEndpoint("2.2.2.2", 30000, "envoy.lb",
                                                 ImmutableMap.of("rack", "rack-2"))),
                clusterName);
    }

    @Test
    void distinctEndpointCollapsesDuplicates() throws IOException {
        // Two Pods on the same node resolve to the same nodeIP:nodePort.
        final String keepId = "dup-keep";
        final String keepCluster = createAndGetClusterName(keepId, watcher("dup-service").build());
        checkEndpointsViaDiscoveryRequest(
                dogma.httpClient().uri(),
                clusterLoadAssignment(keepCluster, endpoint("1.1.1.1", 31000), endpoint("1.1.1.1", 31000)),
                keepCluster);

        final String distinctId = "dup-distinct";
        final String distinctCluster =
                createAndGetClusterName(distinctId, watcher("dup-service").setDistinctEndpoint(true).build());
        checkEndpointsViaDiscoveryRequest(
                dogma.httpClient().uri(),
                clusterLoadAssignment(distinctCluster, endpoint("1.1.1.1", 31000)),
                distinctCluster);
    }

    @Test
    void rejectInvalidMetadataMapping() throws IOException {
        // Missing resource_type.
        assertInvalidArgument("invalid-resource",
                              MetadataMapping.newBuilder()
                                             .setEntryType(MetadataMapping.EntryType.LABEL)
                                             .setSourceKey(ZONE_KEY));
        // Neither source_key nor source_key_prefix set.
        assertInvalidArgument("invalid-source",
                              MetadataMapping.newBuilder()
                                             .setResourceType(MetadataMapping.ResourceType.NODE)
                                             .setEntryType(MetadataMapping.EntryType.LABEL));
    }

    private static void assertInvalidArgument(String aggregatorId, MetadataMapping.Builder mapping)
            throws IOException {
        final ServiceEndpointWatcher watcher = watcher("meta-service").addMetadataMapping(mapping).build();
        final AggregatedHttpResponse response = createAggregator(aggregator(aggregatorId, watcher),
                                                                 aggregatorId, dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);
        assertThatJson(response.contentUtf8()).node("grpc-code").isEqualTo("INVALID_ARGUMENT");
    }

    private static String createAndGetClusterName(String aggregatorId, ServiceEndpointWatcher watcher)
            throws IOException {
        assertOk(createAggregator(aggregator(aggregatorId, watcher), aggregatorId, dogma.httpClient()));
        return "groups/foo/k8s/clusters/" + aggregatorId;
    }

    private static ServiceEndpointWatcher.Builder watcher(String serviceName) {
        final Kubeconfig kubeconfig = Kubeconfig.newBuilder()
                                                .setControlPlaneUrl(client.getMasterUrl().toString())
                                                .setNamespace(client.getNamespace())
                                                .setTrustCerts(true)
                                                .build();
        return ServiceEndpointWatcher.newBuilder().setServiceName(serviceName).setKubeconfig(kubeconfig);
    }

    private static KubernetesEndpointAggregator aggregator(String aggregatorId,
                                                           ServiceEndpointWatcher watcher) {
        return KubernetesEndpointAggregator
                .newBuilder()
                .setName("groups/foo/k8s/endpointAggregators/" + aggregatorId)
                .addLocalityLbEndpoints(KubernetesLocalityLbEndpoints.newBuilder().setWatcher(watcher).build())
                .build();
    }

    private static ClusterLoadAssignment clusterLoadAssignment(String clusterName, LbEndpoint... lbEndpoints) {
        final LocalityLbEndpoints.Builder locality = LocalityLbEndpoints.newBuilder();
        for (LbEndpoint lbEndpoint : lbEndpoints) {
            locality.addLbEndpoints(lbEndpoint);
        }
        return ClusterLoadAssignment.newBuilder()
                                    .setClusterName(clusterName)
                                    .addEndpoints(locality.build())
                                    .build();
    }

    private static LbEndpoint lbEndpoint(String host, int port, String namespace, Map<String, String> fields) {
        final Struct.Builder struct = Struct.newBuilder();
        fields.forEach((key, value) -> struct.putFields(key, Value.newBuilder().setStringValue(value).build()));
        final Metadata metadata = Metadata.newBuilder().putFilterMetadata(namespace, struct.build()).build();
        return endpoint(host, port).toBuilder().setMetadata(metadata).build();
    }

    private static Node newNode(String name, String ip, Map<String, String> labels,
                               Map<String, String> annotations) {
        return new NodeBuilder()
                .withNewMetadata().withName(name).withLabels(labels).withAnnotations(annotations).endMetadata()
                .withNewStatus()
                .addNewAddress().withType("InternalIP").withAddress(ip).endAddress()
                .endStatus()
                .build();
    }

    private static Service newService(String name, Map<String, String> selector, int nodePort) {
        return new ServiceBuilder()
                .withNewMetadata().withName(name).endMetadata()
                .withNewSpec()
                .withType("NodePort")
                .withSelector(selector)
                .addNewPort().withPort(80).withNodePort(nodePort).endPort()
                .endSpec()
                .build();
    }

    private static Pod newPod(String name, String nodeName, Map<String, String> labels) {
        return new PodBuilder()
                .withNewMetadata().withName(name).withLabels(labels).endMetadata()
                .withNewSpec()
                .withNodeName(nodeName)
                .addNewContainer().withName("app").withImage("nginx:1.14.2").endContainer()
                .endSpec()
                .build();
    }
}
