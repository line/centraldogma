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

import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.MirrorException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.xds.k8s.v1.KubernetesEndpointAggregator;
import com.linecorp.centraldogma.xds.k8s.v1.KubernetesLocalityLbEndpoints;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint.Builder;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

class XdsMirrorFileValidatorTest {

    private static final String REPO_NAME = "my-group";

    private static final XdsMirrorFileValidator VALIDATOR = new XdsMirrorFileValidator();

    private static Change<JsonNode> yamlChangeOf(String path, Message proto) {
        try {
            final String json = JSON_MESSAGE_MARSHALLER.writeValueAsString(proto);
            return Change.ofYamlUpsert(path, Jackson.readTree(json));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Cluster sampleCluster() {
        return Cluster.newBuilder()
                      .setName("test-cluster")
                      .setConnectTimeout(Durations.fromSeconds(5))
                      .build();
    }

    private static Listener sampleListener() {
        return Listener.newBuilder()
                       .setName("test-listener")
                       .setApiListener(ApiListener.newBuilder().build())
                       .build();
    }

    private static RouteConfiguration sampleRoute() {
        return RouteConfiguration.newBuilder()
                                 .setName("test-route")
                                 .addVirtualHosts(VirtualHost.newBuilder()
                                                             .setName("local")
                                                             .addDomains("*"))
                                 .build();
    }

    private static ClusterLoadAssignment sampleEndpoint() {
        final Builder endpoint =
                Endpoint.newBuilder().setAddress(Address.newBuilder().setSocketAddress(
                        SocketAddress.newBuilder().setAddress("127.0.0.1").setPortValue(8080)));
        return ClusterLoadAssignment.newBuilder()
                                    .setClusterName("test-cluster")
                                    .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                                     .addLbEndpoints(
                                                                             LbEndpoint.newBuilder()
                                                                                       .setEndpoint(endpoint)))
                                    .build();
    }

    private static KubernetesEndpointAggregator sampleAggregator() {
        return KubernetesEndpointAggregator.newBuilder()
                                           .setName("groups/g1/k8s/endpointAggregators/agg1")
                                           .setClusterName("agg-cluster")
                                           .addLocalityLbEndpoints(
                                                   KubernetesLocalityLbEndpoints.newBuilder())
                                           .build();
    }

    @Test
    void clusterFile_validContent_passes() {
        assertThatCode(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME,
                yamlChangeOf("/clusters/my-cluster.yaml", sampleCluster())))
                .doesNotThrowAnyException();
    }

    @Test
    void listenerFile_validContent_passes() {
        assertThatCode(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME,
                yamlChangeOf("/listeners/my-listener.yaml", sampleListener())))
                .doesNotThrowAnyException();
    }

    @Test
    void routeFile_validContent_passes() {
        assertThatCode(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME,
                yamlChangeOf("/routes/my-route.yaml", sampleRoute())))
                .doesNotThrowAnyException();
    }

    @Test
    void endpointFile_validContent_passes() {
        assertThatCode(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME,
                yamlChangeOf("/endpoints/my-endpoint.yaml", sampleEndpoint())))
                .doesNotThrowAnyException();
    }

    @Test
    void k8sAggregatorFile_validContent_passes() {
        assertThatCode(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME,
                yamlChangeOf("/k8s/endpointAggregators/agg1.yaml", sampleAggregator())))
                .doesNotThrowAnyException();
    }

    @Test
    void nonXdsProject_skipped() {
        assertThatCode(() -> VALIDATOR.validate(
                "other-project", REPO_NAME,
                yamlChangeOf("/clusters/my-cluster.yaml", sampleCluster())))
                .doesNotThrowAnyException();
    }

    @Test
    void removeChange_skipped() {
        assertThatCode(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME,
                Change.ofRemoval("/clusters/my-cluster.yaml")))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidContent_rejected() throws JsonProcessingException {
        final Change<JsonNode> badChange =
                Change.ofYamlUpsert("/clusters/bad.yaml",
                                    Jackson.readTree("{\"not_a_cluster_field\": true}"));
        assertThatThrownBy(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME, badChange))
                .isInstanceOf(MirrorException.class)
                .hasMessageContaining("/clusters/bad.yaml");
    }

    @Test
    void typeMismatch_listenerYamlInClustersDir_rejected() {
        assertThatThrownBy(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME,
                yamlChangeOf("/clusters/wrong-type.yaml", sampleListener())))
                .isInstanceOf(MirrorException.class)
                .hasMessageContaining("/clusters/wrong-type.yaml");
    }

    @Test
    void typeMismatch_clusterYamlInListenersDir_rejected() {
        assertThatThrownBy(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME,
                yamlChangeOf("/listeners/wrong-type.yaml", sampleCluster())))
                .isInstanceOf(MirrorException.class)
                .hasMessageContaining("/listeners/wrong-type.yaml");
    }

    @Test
    void k8sEndpointsFile_rejected() {
        assertThatThrownBy(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME,
                yamlChangeOf("/k8s/endpoints/foo.yaml", sampleEndpoint())))
                .isInstanceOf(MirrorException.class)
                .hasMessageContaining("/k8s/endpoints/")
                .hasMessageContaining("Kubernetes controller");
    }

    @Test
    void unexpectedPath_rejected() {
        assertThatThrownBy(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME,
                Change.ofTextUpsert("/README.md", "hello")))
                .isInstanceOf(MirrorException.class)
                .hasMessageContaining("/README.md")
                .hasMessageContaining("unexpected file path");
    }

    @Test
    void unknownTopLevelDir_rejected() {
        assertThatThrownBy(() -> VALIDATOR.validate(
                XDS_CENTRAL_DOGMA_PROJECT, REPO_NAME,
                yamlChangeOf("/unknown/resource.yaml", sampleCluster())))
                .isInstanceOf(MirrorException.class)
                .hasMessageContaining("/unknown/resource.yaml")
                .hasMessageContaining("unexpected file path");
    }
}
