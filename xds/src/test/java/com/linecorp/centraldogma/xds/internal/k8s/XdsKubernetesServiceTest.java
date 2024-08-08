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
package com.linecorp.centraldogma.xds.internal.k8s;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.k8s.XdsKubernetesService.K8S_WATCHERS_DIRECTORY;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

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
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;
import com.linecorp.centraldogma.xds.k8s.v1.KubernetesConfig;
import com.linecorp.centraldogma.xds.k8s.v1.Watcher;
import com.linecorp.centraldogma.xds.k8s.v1.Watcher.Builder;

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
class XdsKubernetesServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    private KubernetesClient client;

    @BeforeAll
    static void setup() {
        final AggregatedHttpResponse response = createGroup("groups/foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void createWatcherRequest() throws IOException {
        // Prepare Kubernetes resources
        final List<Node> nodes = ImmutableList.of(newNode("1.1.1.1"), newNode("2.2.2.2"), newNode("3.3.3.3"));
        final Deployment deployment = newDeployment();
        final int nodePort = 30000;
        final Service service = newService(nodePort);
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

        final KubernetesConfig kubernetesConfig =
                KubernetesConfig.newBuilder()
                                .setControlPlaneUrl(client.getMasterUrl().toString())
                                .setNamespace(client.getNamespace())
                                .setTrustCerts(true)
                                .build();
        final Watcher.Builder watcherBuilder = Watcher.newBuilder()
                                                      .setName("groups/foo/k8s/watchers/foo-k8s-cluster")
                                                      .setServiceName("nginx-service")
                                                      .setKubernetesConfig(kubernetesConfig);
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST,
                                       "/api/v1/xds/groups/foo/k8s/watchers?watcher_id=foo-k8s-cluster")
                              .contentType(MediaType.JSON_UTF_8)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .build();

        final Watcher watcher = watcherBuilder.build();
        final AggregatedHttpResponse response = dogma.httpClient().blocking().execute(
                headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(watcher));
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
        final String json = response.contentUtf8();
        assertWatcher(json, watcher);
        final Entry<JsonNode> entry =
                dogma.projectManager().get(XDS_CENTRAL_DOGMA_PROJECT).repos().get("foo")
                     .get(Revision.HEAD,
                          Query.ofJson(K8S_WATCHERS_DIRECTORY + "foo-k8s-cluster.json")).join();
        assertWatcher(entry.contentAsText(), watcher);
    }

    private static void assertWatcher(String json, Watcher expected) throws IOException {
        final Builder responseWatcherBuilder = Watcher.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(json, responseWatcherBuilder);
        assertThat(responseWatcherBuilder.build()).isEqualTo(expected);
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

    static Service newService(@Nullable Integer nodePort) {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName("nginx-service")
                .build();
        final ServicePort servicePort = new ServicePortBuilder()
                .withPort(80)
                .withNodePort(nodePort)
                .build();
        final ServiceSpec serviceSpec = new ServiceSpecBuilder()
                .withPorts(servicePort)
                .withSelector(ImmutableMap.of("app", "nginx"))
                .withType("NodePort")
                .build();
        return new ServiceBuilder()
                .withMetadata(metadata)
                .withSpec(serviceSpec)
                .build();
    }

    static Deployment newDeployment() {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName("nginx-deployment")
                .build();
        final LabelSelector selector = new LabelSelectorBuilder()
                .withMatchLabels(ImmutableMap.of("app", "nginx"))
                .build();
        final DeploymentSpec deploymentSpec = new DeploymentSpecBuilder()
                .withReplicas(4)
                .withSelector(selector)
                .withTemplate(newPodTemplate())
                .build();
        return new DeploymentBuilder()
                .withMetadata(metadata)
                .withSpec(deploymentSpec)
                .build();
    }

    private static PodTemplateSpec newPodTemplate() {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withLabels(ImmutableMap.of("app", "nginx"))
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
}
