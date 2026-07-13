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
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.K8S_ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.loadAssignment;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

class XdsEndpointReadServiceTest {

    private static final String GROUP = "read-svc";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @BeforeAll
    static void setup() {
        assertThat(createGroup(GROUP, dogma.httpClient()).status()).isSameAs(HttpStatus.OK);
    }

    // ---- listEndpoints -------------------------------------------------------

    @Test
    void listEndpoints_jsonFileAppearsWithJsonType() throws Exception {
        final ClusterLoadAssignment endpoint =
                loadAssignment("groups/" + GROUP + "/endpoints/list-json", "127.0.0.1", 8080);
        pushJsonEndpoint("list-json", endpoint);

        final AggregatedHttpResponse response = listEndpoints();
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        final JsonNode body = Jackson.readTree(response.contentUtf8());
        final JsonNode entry = findEntryByPath(body, "/endpoints/list-json.json");
        assertThat(entry).isNotNull();
        assertThat(entry.get("type").asText()).isEqualTo("JSON");
        assertThat(entry.get("revision").asInt()).isPositive();
    }

    @Test
    void listEndpoints_yamlFileAppearsWithYamlType() throws Exception {
        final ClusterLoadAssignment endpoint =
                loadAssignment("groups/" + GROUP + "/endpoints/list-yaml", "127.0.0.1", 8081);
        pushYamlEndpoint("list-yaml", endpoint);

        final AggregatedHttpResponse response = listEndpoints();
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        final JsonNode body = Jackson.readTree(response.contentUtf8());
        final JsonNode entry = findEntryByPath(body, "/endpoints/list-yaml.yaml");
        assertThat(entry).isNotNull();
        assertThat(entry.get("type").asText()).isEqualTo("YAML");
        assertThat(entry.get("revision").asInt()).isPositive();
    }

    @Test
    void listEndpoints_mixedJsonAndYamlBothAppear() throws Exception {
        final ClusterLoadAssignment jsonEndpoint =
                loadAssignment("groups/" + GROUP + "/endpoints/list-mix-json", "127.0.0.1", 8082);
        pushJsonEndpoint("list-mix-json", jsonEndpoint);

        final ClusterLoadAssignment yamlEndpoint =
                loadAssignment("groups/" + GROUP + "/endpoints/list-mix-yaml", "127.0.0.1", 8083);
        pushYamlEndpoint("list-mix-yaml", yamlEndpoint);

        final AggregatedHttpResponse response = listEndpoints();
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        final JsonNode body = Jackson.readTree(response.contentUtf8());
        assertThat(findEntryByPath(body, "/endpoints/list-mix-json.json")).isNotNull();
        assertThat(findEntryByPath(body, "/endpoints/list-mix-yaml.yaml")).isNotNull();
    }

    @Test
    void listEndpoints_includesK8sJsonAndYamlEndpoints() throws Exception {
        pushJsonK8sEndpoint("list-k8s-json",
                            loadAssignment("groups/" + GROUP + "/clusters/list-k8s-json",
                                           "10.0.0.1", 9090));
        pushYamlK8sEndpoint("list-k8s-yaml",
                            loadAssignment("groups/" + GROUP + "/clusters/list-k8s-yaml",
                                           "10.0.0.2", 9091));

        final AggregatedHttpResponse response = listEndpoints();
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        final JsonNode body = Jackson.readTree(response.contentUtf8());
        final JsonNode jsonEntry = findEntryByPath(body, "/k8s/endpoints/list-k8s-json.json");
        assertThat(jsonEntry).isNotNull();
        assertThat(jsonEntry.get("type").asText()).isEqualTo("JSON");

        final JsonNode yamlEntry = findEntryByPath(body, "/k8s/endpoints/list-k8s-yaml.yaml");
        assertThat(yamlEntry).isNotNull();
        assertThat(yamlEntry.get("type").asText()).isEqualTo("YAML");
    }

    // ---- getEndpoint ---------------------------------------------------------

    @Test
    void getEndpoint_jsonFileReturnsContentWithJsonType() throws Exception {
        final ClusterLoadAssignment endpoint =
                loadAssignment("groups/" + GROUP + "/endpoints/get-json", "127.0.0.1", 8090);
        pushJsonEndpoint("get-json", endpoint);

        final AggregatedHttpResponse response = getEndpoint("get-json");
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        final JsonNode body = Jackson.readTree(response.contentUtf8());
        assertThat(body.get("path").asText()).isEqualTo("/endpoints/get-json.json");
        assertThat(body.get("type").asText()).isEqualTo("JSON");
        assertThat(body.get("revision").asInt()).isPositive();
        // Verify the endpoint content is present.
        assertThat(body.get("content")).isNotNull();
        assertThat(body.get("content").toString()).contains("127.0.0.1");
    }

    @Test
    void getEndpoint_yamlFileReturnsContentWithYamlType() throws Exception {
        final ClusterLoadAssignment endpoint =
                loadAssignment("groups/" + GROUP + "/endpoints/get-yaml", "127.0.0.1", 8091);
        pushYamlEndpoint("get-yaml", endpoint);

        final AggregatedHttpResponse response = getEndpoint("get-yaml");
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        final JsonNode body = Jackson.readTree(response.contentUtf8());
        assertThat(body.get("path").asText()).isEqualTo("/endpoints/get-yaml.yaml");
        assertThat(body.get("type").asText()).isEqualTo("YAML");
        assertThat(body.get("revision").asInt()).isPositive();
        // Content must be the deserialized endpoint, regardless of the on-disk format.
        assertThat(body.get("content")).isNotNull();
        assertThat(body.get("content").toString()).contains("127.0.0.1");
    }

    @Test
    void getEndpoint_notFound() {
        final AggregatedHttpResponse response = getEndpoint("does-not-exist");
        assertThat(response.status()).isNotEqualTo(HttpStatus.OK);
    }

    // ---- getK8sEndpoint ------------------------------------------------------

    @Test
    void getK8sEndpoint_jsonFileReturnsContentWithJsonType() throws Exception {
        final ClusterLoadAssignment endpoint =
                loadAssignment("groups/" + GROUP + "/clusters/get-k8s-json", "10.0.0.3", 9092);
        pushJsonK8sEndpoint("get-k8s-json", endpoint);

        final AggregatedHttpResponse response = getK8sEndpoint("get-k8s-json");
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        final JsonNode body = Jackson.readTree(response.contentUtf8());
        assertThat(body.get("path").asText()).isEqualTo("/k8s/endpoints/get-k8s-json.json");
        assertThat(body.get("type").asText()).isEqualTo("JSON");
        assertThat(body.get("content").toString()).contains("10.0.0.3");
    }

    @Test
    void getK8sEndpoint_yamlFileReturnsContentWithYamlType() throws Exception {
        final ClusterLoadAssignment endpoint =
                loadAssignment("groups/" + GROUP + "/clusters/get-k8s-yaml", "10.0.0.4", 9093);
        pushYamlK8sEndpoint("get-k8s-yaml", endpoint);

        final AggregatedHttpResponse response = getK8sEndpoint("get-k8s-yaml");
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        final JsonNode body = Jackson.readTree(response.contentUtf8());
        assertThat(body.get("path").asText()).isEqualTo("/k8s/endpoints/get-k8s-yaml.yaml");
        assertThat(body.get("type").asText()).isEqualTo("YAML");
        assertThat(body.get("content").toString()).contains("10.0.0.4");
    }

    // ---- helpers -------------------------------------------------------------

    private static AggregatedHttpResponse listEndpoints() {
        return dogma.httpClient()
                    .get("/api/v1/xds/groups/" + GROUP + "/endpoints")
                    .aggregate().join();
    }

    private static AggregatedHttpResponse getEndpoint(String id) {
        return dogma.httpClient()
                    .get("/api/v1/xds/groups/" + GROUP + "/endpoints/" + id)
                    .aggregate().join();
    }

    private static AggregatedHttpResponse getK8sEndpoint(String id) {
        return dogma.httpClient()
                    .get("/api/v1/xds/groups/" + GROUP + "/k8s/endpoints/" + id)
                    .aggregate().join();
    }

    private static void pushJsonEndpoint(String endpointId, ClusterLoadAssignment endpoint)
            throws Exception {
        dogma.client().forRepo(XDS_CENTRAL_DOGMA_PROJECT, GROUP)
             .commit("Add JSON endpoint: " + endpointId,
                     Change.ofJsonUpsert(ENDPOINTS_DIRECTORY + endpointId + ".json",
                                         Jackson.readTree(
                                                 JSON_MESSAGE_MARSHALLER.writeValueAsString(endpoint))))
             .push().join();
    }

    private static void pushYamlEndpoint(String endpointId, ClusterLoadAssignment endpoint)
            throws Exception {
        dogma.client().forRepo(XDS_CENTRAL_DOGMA_PROJECT, GROUP)
             .commit("Add YAML endpoint: " + endpointId,
                     Change.ofYamlUpsert(ENDPOINTS_DIRECTORY + endpointId + ".yaml",
                                         JSON_MESSAGE_MARSHALLER.writeValueAsString(endpoint)))
             .push().join();
    }

    private static void pushJsonK8sEndpoint(String endpointId, ClusterLoadAssignment endpoint)
            throws Exception {
        dogma.client().forRepo(XDS_CENTRAL_DOGMA_PROJECT, GROUP)
             .commit("Add JSON k8s endpoint: " + endpointId,
                     Change.ofJsonUpsert(K8S_ENDPOINTS_DIRECTORY + endpointId + ".json",
                                         Jackson.readTree(
                                                 JSON_MESSAGE_MARSHALLER.writeValueAsString(endpoint))))
             .push().join();
    }

    private static void pushYamlK8sEndpoint(String endpointId, ClusterLoadAssignment endpoint)
            throws Exception {
        dogma.client().forRepo(XDS_CENTRAL_DOGMA_PROJECT, GROUP)
             .commit("Add YAML k8s endpoint: " + endpointId,
                     Change.ofYamlUpsert(K8S_ENDPOINTS_DIRECTORY + endpointId + ".yaml",
                                         JSON_MESSAGE_MARSHALLER.writeValueAsString(endpoint)))
             .push().join();
    }

    /**
     * Finds the array element whose {@code "path"} field equals {@code path}, or returns {@code null}.
     */
    private static JsonNode findEntryByPath(JsonNode array, String path) {
        for (JsonNode element : array) {
            if (path.equals(element.get("path").asText())) {
                return element;
            }
        }
        return null;
    }
}
