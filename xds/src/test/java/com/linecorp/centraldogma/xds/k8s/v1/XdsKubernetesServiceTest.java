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
import static com.linecorp.centraldogma.internal.CredentialUtil.projectCredentialResourceName;
import static com.linecorp.centraldogma.xds.endpoint.v1.XdsEndpointServiceTest.checkEndpointsViaDiscoveryRequest;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.endpoint;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService.K8S_ENDPOINT_AGGREGATORS_DIRECTORY;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
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

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
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
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.ServerRequest;
import io.fabric8.mockwebserver.ServerResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class XdsKubernetesServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    private static CapturingKubernetesMixedDispatcher dispatcher;
    private static KubernetesMockServer mock;
    private static NamespacedKubernetesClient client;

    @BeforeAll
    static void setup() {
        setUpK8s();
        putCredential();

        final AggregatedHttpResponse response = createGroup("foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        final List<Node> nodes = ImmutableList.of(newNode("1.1.1.1"), newNode("2.2.2.2"));
        final Map<String, String> selector = ImmutableMap.of("app", "nginx");
        final Deployment deployment = newDeployment("nginx-deployment", selector);
        final Service service = newService("nginx-service", selector);
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

    private static void setUpK8s() {
        final Map<ServerRequest, Queue<ServerResponse>> responses = new HashMap<>();
        dispatcher = new CapturingKubernetesMixedDispatcher(responses);
        mock = new KubernetesMockServer(new Context(), new MockWebServer(), responses, dispatcher, false);
        mock.init();
        client = mock.createClient();
    }

    private static void putCredential() {
        final ImmutableMap<String, String> credential =
                ImmutableMap.of("type", "access_token",
                                "id", "my-credential",
                                "resourceName", projectCredentialResourceName(XDS_CENTRAL_DOGMA_PROJECT,
                                                                              "my-credential"),
                                "accessToken", "secret");
        dogma.httpClient().prepare()
             .post("/api/v1/projects/@xds/credentials")
             .header(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
             .contentJson(credential).execute().aggregate().join();
    }

    @AfterAll
    static void cleanupK8sResources() {
        client.nodes().list().getItems().forEach(
                node -> client.nodes().withName(node.getMetadata().getName()).delete());
        client.pods().list().getItems().forEach(
                pod -> client.pods().withName(pod.getMetadata().getName()).delete());
        client.apps().deployments().list().getItems().forEach(
                deployment -> client.apps().deployments().withName(deployment.getMetadata().getName())
                                    .delete());
        client.services().list().getItems().forEach(
                service -> client.services().withName(service.getMetadata().getName()).delete());
        client.close();
        mock.destroy();
    }

    @BeforeEach
    void clearQueue() {
        dispatcher.queue().clear();
    }

    @Test
    void invalidProperty() throws IOException {
        final String aggregatorId = "foo-cluster";
        KubernetesEndpointAggregator aggregator =
                aggregator(aggregatorId, "invalid-service-name", "my-credential");
        AggregatedHttpResponse response = createAggregator(aggregator, aggregatorId);
        assertThat(response.status()).isSameAs(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.contentUtf8()).contains("Failed to retrieve k8s endpoints");

        aggregator = aggregator(aggregatorId, "nginx-service", "invalid-credential-id");
        response = createAggregator(aggregator, aggregatorId);
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);
        assertThatJson(response.contentUtf8())
                .node("grpc-code").isEqualTo("INVALID_ARGUMENT")
                .node("message").isEqualTo(
                        "failed to find credential file " +
                        "'/credentials/invalid-credential-id.json' in @xds/meta");
    }

    @Test
    void createEndpointAggregatorsRequest() throws IOException {
        final String aggregatorId = "foo-k8s-cluster/1";
        final String clusterName = "groups/foo/k8s/clusters/" + aggregatorId;
        final KubernetesEndpointAggregator aggregator = aggregator(aggregatorId);
        final AggregatedHttpResponse response = createAggregator(aggregator, aggregatorId);
        assertOk(response);
        final String json = response.contentUtf8();
        final KubernetesEndpointAggregator expectedAggregator =
                aggregator.toBuilder().setClusterName(clusterName) // cluster name is set by the service.
                          .build();
        assertAggregator(json, expectedAggregator);
        final Repository fooGroup = dogma.projectManager().get(XDS_CENTRAL_DOGMA_PROJECT).repos().get("foo");
        final Entry<JsonNode> entry =
                fooGroup.get(Revision.HEAD, Query.ofJson(
                        K8S_ENDPOINT_AGGREGATORS_DIRECTORY + aggregatorId + ".json")).join();
        assertAggregator(entry.contentAsText(), expectedAggregator);
        final ClusterLoadAssignment loadAssignment = clusterLoadAssignment(clusterName, 30000);
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), loadAssignment, clusterName);

        // Check if the next commit contains all endpoints.
        // In the plugin, KubernetesEndpointsUpdater makes a commit 1 second after addListener is called
        // so that endpoints are not updated one by one.
        final Entry<JsonNode> clusterEntry =
                fooGroup.get(entry.revision().forward(1),
                             Query.ofJson("/k8s/endpoints/" + aggregatorId + ".json"))
                        .join();
        final ClusterLoadAssignment.Builder clusterLoadAssignmentBuilder = ClusterLoadAssignment.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(clusterEntry.contentAsText(), clusterLoadAssignmentBuilder);
        assertThat(clusterLoadAssignmentBuilder.build()).isEqualTo(loadAssignment);

        dispatcher.queue().forEach(req -> {
            // All requests contain the credential.
            assertThat(req.getHeaders().get(HttpHeaderNames.AUTHORIZATION.toString()))
                    .describedAs("Request: %s does not contain the credential", req)
                    .isEqualTo("Bearer secret");
        });
    }

    private static KubernetesEndpointAggregator aggregator(String aggregatorId) {
        return aggregator(aggregatorId, "nginx-service", "my-credential");
    }

    private static KubernetesEndpointAggregator aggregator(
            String aggregatorId, String serviceName, String credentialId) {
        final Kubeconfig kubeconfig = Kubeconfig.newBuilder()
                                                .setControlPlaneUrl(client.getMasterUrl().toString())
                                                .setNamespace(client.getNamespace())
                                                .setCredentialId(credentialId)
                                                .setTrustCerts(true)
                                                .build();
        final ServiceEndpointWatcher watcher = ServiceEndpointWatcher.newBuilder()
                                                                     .setServiceName(serviceName)
                                                                     .setKubeconfig(kubeconfig)
                                                                     .build();
        return KubernetesEndpointAggregator
                .newBuilder().setName("groups/foo/k8s/endpointAggregators/" + aggregatorId)
                .addLocalityLbEndpoints(KubernetesLocalityLbEndpoints.newBuilder()
                                                                     .setWatcher(watcher)
                                                                     .build())
                .build();
    }

    private static AggregatedHttpResponse createAggregator(
            KubernetesEndpointAggregator aggregator, String aggregatorId) throws IOException {
        return createAggregator(aggregator, aggregatorId, dogma.httpClient());
    }

    static AggregatedHttpResponse createAggregator(
            KubernetesEndpointAggregator aggregator, String aggregatorId,
            WebClient webClient) throws IOException {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST,
                                       "/api/v1/xds/groups/foo/k8s/endpointAggregators?" +
                                       "aggregator_id=" + aggregatorId)
                              .contentType(MediaType.JSON_UTF_8)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .build();

        return webClient.blocking().execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(aggregator));
    }

    static void assertOk(AggregatedHttpResponse response) {
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
    }

    static void assertAggregator(
            String json, KubernetesEndpointAggregator expected) throws IOException {
        final KubernetesEndpointAggregator.Builder responseBuilder = KubernetesEndpointAggregator.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(json, responseBuilder);
        assertThat(responseBuilder.build()).isEqualTo(expected);
    }

    private static ClusterLoadAssignment clusterLoadAssignment(String clusterName, int port) {
        return ClusterLoadAssignment.newBuilder()
                                    .setClusterName(clusterName)
                                    .addEndpoints(
                                            LocalityLbEndpoints.newBuilder()
                                                               .addLbEndpoints(endpoint("1.1.1.1", port))
                                                               .addLbEndpoints(endpoint("2.2.2.2", port))
                                                               .build())
                                    .build();
    }

    @Test
    void updateAggregator() throws IOException {
        final String aggregatorId = "foo-k8s-cluster/2";
        final KubernetesEndpointAggregator aggregator = aggregator(aggregatorId);
        AggregatedHttpResponse response = createAggregator(aggregator, aggregatorId);
        assertOk(response);
        final String clusterName = "groups/foo/k8s/clusters/" + aggregatorId;
        final ClusterLoadAssignment loadAssignment = clusterLoadAssignment(clusterName, 30000);
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), loadAssignment, clusterName);

        final KubernetesLocalityLbEndpoints localityLbEndpoints = aggregator.getLocalityLbEndpoints(0);
        final ServiceEndpointWatcher updatedWatcher =
                localityLbEndpoints.getWatcher().toBuilder().setPortName("https").build();
        final KubernetesLocalityLbEndpoints updatedLbEndpoints =
                localityLbEndpoints.toBuilder().setWatcher(updatedWatcher).build();
        final KubernetesEndpointAggregator updatingAggregator =
                aggregator.toBuilder().setLocalityLbEndpoints(0, updatedLbEndpoints).build();
        response = updateAggregator(updatingAggregator, aggregatorId, dogma.httpClient());

        assertOk(response);
        assertAggregator(response.contentUtf8(),
                         // cluster name is set by the service.
                         updatingAggregator.toBuilder().setClusterName(clusterName).build());
        final ClusterLoadAssignment loadAssignment2 = clusterLoadAssignment(clusterName, 30001);
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), loadAssignment2, clusterName);
    }

    static AggregatedHttpResponse updateAggregator(
            KubernetesEndpointAggregator aggregator,
            String aggregatorId, WebClient webClient) throws IOException {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.PATCH,
                                       "/api/v1/xds/groups/foo/k8s/endpointAggregators/" + aggregatorId)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return webClient.execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(aggregator))
                        .aggregate().join();
    }

    @Test
    void deleteAggregator() throws IOException {
        final String aggregatorId = "foo-k8s-cluster/3";
        final KubernetesEndpointAggregator aggregator = aggregator(aggregatorId);
        AggregatedHttpResponse response = createAggregator(aggregator, aggregatorId);
        assertOk(response);
        final String clusterName = "groups/foo/k8s/clusters/" + aggregatorId;
        final ClusterLoadAssignment loadAssignment = clusterLoadAssignment(clusterName, 30000);
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), loadAssignment, clusterName);
        response = deleteAggregator(aggregator.getName());
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), null, clusterName);
    }

    private static AggregatedHttpResponse deleteAggregator(String aggregatorName) {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.DELETE, "/api/v1/xds/" + aggregatorName)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .build();
        return dogma.httpClient().execute(headers).aggregate().join();
    }

    static Node newNode(String ip) {
        return newNode(ip, "InternalIP");
    }

    private static Node newNode(String ip, String type) {
        final NodeAddress nodeAddress = new NodeAddressBuilder()
                .withType(type)
                .withAddress(ip)
                .build();
        final NodeStatus nodeStatus = new NodeStatusBuilder()
                .withAddresses(nodeAddress)
                .build();
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName("node-" + ip)
                .build();
        return new NodeBuilder()
                .withMetadata(metadata)
                .withStatus(nodeStatus)
                .build();
    }

    static Service newService(String serviceName, Map<String, String> selector) {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName(serviceName)
                .build();
        final ServicePort servicePort = new ServicePortBuilder()
                .withPort(80)
                .withNodePort(30000)
                .build();
        final ServicePort httpsServicePort = new ServicePortBuilder()
                .withPort(443)
                .withNodePort(30001)
                .withName("https")
                .build();
        final ServiceSpec serviceSpec = new ServiceSpecBuilder()
                .withPorts(servicePort, httpsServicePort)
                .withSelector(selector)
                .withType("NodePort")
                .build();
        return new ServiceBuilder()
                .withMetadata(metadata)
                .withSpec(serviceSpec)
                .build();
    }

    static Deployment newDeployment(String deploymentName, Map<String, String> matchLabels) {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName(deploymentName)
                .build();
        final LabelSelector selector = new LabelSelectorBuilder()
                .withMatchLabels(matchLabels)
                .build();
        final DeploymentSpec deploymentSpec = new DeploymentSpecBuilder()
                .withReplicas(4)
                .withSelector(selector)
                .withTemplate(newPodTemplate(matchLabels))
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

    static Pod newPod(PodTemplateSpec template, String newNodeName) {
        final PodSpec spec = template.getSpec()
                                     .toBuilder()
                                     .withNodeName(newNodeName)
                                     .build();
        final ObjectMeta metadata = template.getMetadata()
                                            .toBuilder()
                                            .withName("nginx-pod-" + newNodeName)
                                            .build();
        return new PodBuilder()
                .withMetadata(metadata)
                .withSpec(spec)
                .build();
    }

    private static class CapturingKubernetesMixedDispatcher extends KubernetesMixedDispatcher {

        private final BlockingQueue<RecordedRequest> queue = new ArrayBlockingQueue<>(16);

        CapturingKubernetesMixedDispatcher(Map<ServerRequest, Queue<ServerResponse>> responses) {
            super(responses);
        }

        BlockingQueue<RecordedRequest> queue() {
            return queue;
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            queue.add(request);
            return super.dispatch(request);
        }
    }
}
