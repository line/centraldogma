/*
 * Copyright 2026 LINE Corporation
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
package com.linecorp.centraldogma.it.xds.k8s;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.it.xds.k8s.LabelBasedNodeIpExtractor.NODE_IP_LABEL_PROPERTY;
import static com.linecorp.centraldogma.server.storage.project.InternalProjectConstants.INTERNAL_PROJECT_XDS;
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

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;
import com.linecorp.centraldogma.xds.internal.XdsResourceManager;
import com.linecorp.centraldogma.xds.k8s.v1.Kubeconfig;
import com.linecorp.centraldogma.xds.k8s.v1.KubernetesEndpointAggregator;
import com.linecorp.centraldogma.xds.k8s.v1.KubernetesLocalityLbEndpoints;
import com.linecorp.centraldogma.xds.k8s.v1.ServiceEndpointWatcher;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.NodeAddressBuilder;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeStatus;
import io.fabric8.kubernetes.api.model.NodeStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

@EnableKubernetesMockClient(crud = true)
class XdsKubernetesNodeIpExtractorTest {

    private static final String K8S_ENDPOINT_AGGREGATORS_DIRECTORY = "/k8s/endpointAggregators/";
    private static final String K8S_ENDPOINTS_DIRECTORY = "/k8s/endpoints/";

    private static final String EXTERNAL_IP_LABEL = "centraldogma.linecorp.com/external-ip";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    static KubernetesClient client;

    @BeforeAll
    static void setup() {
        final AggregatedHttpResponse response = createGroup("foo");
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        final List<Node> nodes = ImmutableList.of(
                newNodeWithLabel("1.1.1.1", EXTERNAL_IP_LABEL, "10.0.0.1"),
                newNodeWithLabel("2.2.2.2", EXTERNAL_IP_LABEL, "10.0.0.2"));
        final Map<String, String> selector = ImmutableMap.of("app", "nginx");
        final Deployment deployment = newDeployment("nginx-deployment", selector);
        final Service service = newService("nginx-service", selector);
        final List<Pod> pods = nodes.stream()
                                    .map(node -> node.getMetadata().getName())
                                    .map(nodeName -> newPod(deployment.getSpec().getTemplate(), nodeName))
                                    .collect(toImmutableList());

        for (Node node : nodes) {
            client.nodes().resource(node).create();
        }
        for (Pod pod : pods) {
            client.pods().resource(pod).create();
        }
        client.apps().deployments().resource(deployment).create();
        client.services().resource(service).create();
    }

    @Test
    void extractsNodeIpFromLabel() throws Exception {
        LabelBasedNodeIpExtractor.invocations().clear();

        final String aggregatorId = "label-extractor-cluster";
        final ServiceEndpointWatcher watcher = ServiceEndpointWatcher
                .newBuilder()
                .setServiceName("nginx-service")
                .setKubeconfig(Kubeconfig.newBuilder()
                                         .setControlPlaneUrl(client.getMasterUrl().toString())
                                         .setNamespace(client.getNamespace())
                                         .setTrustCerts(true)
                                         .build())
                .putAdditionalProperties(NODE_IP_LABEL_PROPERTY, EXTERNAL_IP_LABEL)
                .build();
        final KubernetesEndpointAggregator aggregator = KubernetesEndpointAggregator
                .newBuilder()
                .setName("groups/foo/k8s/endpointAggregators/" + aggregatorId)
                .addLocalityLbEndpoints(KubernetesLocalityLbEndpoints.newBuilder()
                                                                     .setWatcher(watcher)
                                                                     .build())
                .build();

        final AggregatedHttpResponse response = createAggregator(aggregator, aggregatorId);
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");

        final Repository fooGroup = dogma.projectManager().get(INTERNAL_PROJECT_XDS)
                                         .repos().get("foo");
        final Entry<JsonNode> aggregatorEntry =
                fooGroup.get(Revision.HEAD, Query.ofJson(
                        K8S_ENDPOINT_AGGREGATORS_DIRECTORY + aggregatorId + ".json")).join();

        // KubernetesEndpointsUpdater commits the resolved endpoints in the next revision.
        await().until(() -> fooGroup.normalizeNow(Revision.HEAD)
                                    .equals(aggregatorEntry.revision().forward(1)));

        final Entry<JsonNode> endpointEntry = fooGroup.get(
                Revision.HEAD, Query.ofJson(K8S_ENDPOINTS_DIRECTORY + aggregatorId + ".json")).join();

        // The endpoints must use the label values resolved by LabelBasedNodeIpExtractor,
        // not the default InternalIP addresses (1.1.1.1 / 2.2.2.2).
        assertThatJson(endpointEntry.content()).isEqualTo(
                '{' +
                "  \"clusterName\": \"groups/foo/k8s/clusters/" + aggregatorId + "\"," +
                "  \"endpoints\": [ {" +
                "    \"lbEndpoints\": [ {" +
                "      \"endpoint\": {" +
                "        \"address\": {" +
                "          \"socketAddress\": {" +
                "            \"address\": \"10.0.0.1\"," +
                "            \"portValue\": 30000" +
                "          }" +
                "        }" +
                "      }" +
                "    }, {" +
                "      \"endpoint\": {" +
                "        \"address\": {" +
                "          \"socketAddress\": {" +
                "            \"address\": \"10.0.0.2\"," +
                "            \"portValue\": 30000" +
                "          }" +
                "        }" +
                "      }" +
                "    } ]" +
                "  } ]" +
                '}');

        // The SPI must have been invoked with the watcher we configured.
        assertThat(LabelBasedNodeIpExtractor.invocations()).isNotEmpty();
        assertThat(LabelBasedNodeIpExtractor.invocations())
                .allSatisfy(invokedWatcher -> assertThat(
                        invokedWatcher.getAdditionalPropertiesMap())
                        .containsEntry(NODE_IP_LABEL_PROPERTY, EXTERNAL_IP_LABEL));
    }

    @Test
    void fallsBackToInternalIpWhenLabelKeyIsAbsent() throws Exception {
        LabelBasedNodeIpExtractor.invocations().clear();

        final String aggregatorId = "fallback-internal-ip-cluster";
        final ServiceEndpointWatcher watcher = ServiceEndpointWatcher
                .newBuilder()
                .setServiceName("nginx-service")
                .setKubeconfig(Kubeconfig.newBuilder()
                                         .setControlPlaneUrl(client.getMasterUrl().toString())
                                         .setNamespace(client.getNamespace())
                                         .setTrustCerts(true)
                                         .build())
                // No nodeIpLabel property — should fall back to InternalIP.
                .build();
        final KubernetesEndpointAggregator aggregator = KubernetesEndpointAggregator
                .newBuilder()
                .setName("groups/foo/k8s/endpointAggregators/" + aggregatorId)
                .addLocalityLbEndpoints(KubernetesLocalityLbEndpoints.newBuilder()
                                                                     .setWatcher(watcher)
                                                                     .build())
                .build();

        final AggregatedHttpResponse response = createAggregator(aggregator, aggregatorId);
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");

        final Repository fooGroup = dogma.projectManager().get(INTERNAL_PROJECT_XDS)
                                         .repos().get("foo");
        final Entry<JsonNode> aggregatorEntry =
                fooGroup.get(Revision.HEAD, Query.ofJson(
                        K8S_ENDPOINT_AGGREGATORS_DIRECTORY + aggregatorId + ".json")).join();
        await().until(() -> fooGroup.normalizeNow(Revision.HEAD)
                                    .equals(aggregatorEntry.revision().forward(1)));

        final Entry<JsonNode> endpointEntry = fooGroup.get(
                Revision.HEAD, Query.ofJson(K8S_ENDPOINTS_DIRECTORY + aggregatorId + ".json")).join();
        assertThatJson(endpointEntry.content()).isEqualTo(
                '{' +
                "  \"clusterName\": \"groups/foo/k8s/clusters/" + aggregatorId + "\"," +
                "  \"endpoints\": [ {" +
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
                "  } ]" +
                '}');

        assertThat(LabelBasedNodeIpExtractor.invocations()).isNotEmpty();
        assertThat(LabelBasedNodeIpExtractor.invocations())
                .allSatisfy(invokedWatcher -> assertThat(
                        invokedWatcher.getAdditionalPropertiesMap())
                        .doesNotContainKey(NODE_IP_LABEL_PROPERTY));
    }

    @Test
    void aggregatorFailsWhenExtractorReturnsNullForAllNodes() throws Exception {
        LabelBasedNodeIpExtractor.invocations().clear();

        final String aggregatorId = "missing-label-cluster";
        final ServiceEndpointWatcher watcher = ServiceEndpointWatcher
                .newBuilder()
                .setServiceName("nginx-service")
                .setKubeconfig(Kubeconfig.newBuilder()
                                         .setControlPlaneUrl(client.getMasterUrl().toString())
                                         .setNamespace(client.getNamespace())
                                         .setTrustCerts(true)
                                         .build())
                // No node carries this label, so the extractor returns null for every node and
                // the resulting KubernetesEndpointGroup ends up with zero endpoints.
                .putAdditionalProperties(NODE_IP_LABEL_PROPERTY, "non-existent-label")
                .build();
        final KubernetesEndpointAggregator aggregator = KubernetesEndpointAggregator
                .newBuilder()
                .setName("groups/foo/k8s/endpointAggregators/" + aggregatorId)
                .addLocalityLbEndpoints(KubernetesLocalityLbEndpoints.newBuilder()
                                                                     .setWatcher(watcher)
                                                                     .build())
                .build();

        final AggregatedHttpResponse response = createAggregator(aggregator, aggregatorId);
        assertThat(response.status()).isSameAs(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.contentUtf8()).contains("Failed to retrieve k8s endpoints");

        assertThat(LabelBasedNodeIpExtractor.invocations()).isNotEmpty();
    }

    private static AggregatedHttpResponse createGroup(String groupId) {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST, "/api/v1/xds/groups?group_id=" + groupId)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return dogma.httpClient().execute(headers, "{\"name\":\"groups/" + groupId + "\"}")
                    .aggregate().join();
    }

    private static AggregatedHttpResponse createAggregator(
            KubernetesEndpointAggregator aggregator, String aggregatorId) throws IOException {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST,
                                       "/api/v1/xds/groups/foo/k8s/endpointAggregators?" +
                                       "aggregator_id=" + aggregatorId)
                              .contentType(MediaType.JSON_UTF_8)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .build();
        return dogma.httpClient().blocking().execute(
                headers, XdsResourceManager.JSON_MESSAGE_MARSHALLER.writeValueAsString(aggregator));
    }

    private static Node newNodeWithLabel(String internalIp, String labelKey, String labelValue) {
        final NodeAddress nodeAddress = new NodeAddressBuilder()
                .withType("InternalIP")
                .withAddress(internalIp)
                .build();
        final NodeStatus nodeStatus = new NodeStatusBuilder()
                .withAddresses(nodeAddress)
                .build();
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName("node-" + internalIp)
                .withLabels(ImmutableMap.of(labelKey, labelValue))
                .build();
        return new NodeBuilder()
                .withMetadata(metadata)
                .withStatus(nodeStatus)
                .build();
    }

    private static Service newService(String serviceName, Map<String, String> selector) {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName(serviceName)
                .build();
        final ServicePort servicePort = new ServicePortBuilder()
                .withPort(80)
                .withNodePort(30000)
                .build();
        final ServiceSpec serviceSpec = new ServiceSpecBuilder()
                .withPorts(servicePort)
                .withSelector(selector)
                .withType("NodePort")
                .build();
        return new ServiceBuilder()
                .withMetadata(metadata)
                .withSpec(serviceSpec)
                .build();
    }

    private static Deployment newDeployment(String deploymentName, Map<String, String> matchLabels) {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName(deploymentName)
                .build();
        final LabelSelector selector = new LabelSelectorBuilder()
                .withMatchLabels(matchLabels)
                .build();
        final PodTemplateSpec podTemplate = newPodTemplate(matchLabels);
        final DeploymentSpec deploymentSpec = new DeploymentSpecBuilder()
                .withReplicas(2)
                .withSelector(selector)
                .withTemplate(podTemplate)
                .build();
        return new DeploymentBuilder()
                .withMetadata(metadata)
                .withSpec(deploymentSpec)
                .build();
    }

    private static PodTemplateSpec newPodTemplate(Map<String, String> matchLabels) {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withLabels(matchLabels)
                .build();
        final Container container = new ContainerBuilder()
                .withName("nginx")
                .withImage("nginx:1.14.2")
                .withPorts(new ContainerPortBuilder()
                                   .withContainerPort(8080)
                                   .build())
                .build();
        final PodSpec spec = new PodSpecBuilder()
                .withContainers(container)
                .build();
        return new PodTemplateSpecBuilder()
                .withMetadata(metadata)
                .withSpec(spec)
                .build();
    }

    private static Pod newPod(PodTemplateSpec template, String nodeName) {
        final PodSpec spec = template.getSpec()
                                     .toBuilder()
                                     .withNodeName(nodeName)
                                     .build();
        final ObjectMeta metadata = template.getMetadata()
                                            .toBuilder()
                                            .withName("nginx-pod-" + nodeName)
                                            .build();
        return new PodBuilder()
                .withMetadata(metadata)
                .withSpec(spec)
                .build();
    }
}
