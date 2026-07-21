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
package com.linecorp.centraldogma.xds.internal;

import static com.linecorp.centraldogma.server.internal.storage.InternalProjectConstants.INTERNAL_PROJECT_XDS;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.CLUSTERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.LISTENERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.ROUTES_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.cluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createCluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createEndpoint;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createListener;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createRoute;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.exampleListener;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.loadAssignment;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.routeConfiguration;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.updateCluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.updateListener;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.updateRoute;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

class XdsLegacyJsonCompatibilityTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    private static final String GROUP = "compat-test";

    @BeforeAll
    static void setup() {
        assertThat(createGroup(GROUP, dogma.httpClient()).status()).isSameAs(HttpStatus.OK);
    }

    // ---- Cluster ----

    @Test
    void createCluster_conflictsWithLegacyJson() throws IOException {
        final Cluster cluster = cluster("ignored", 1);
        pushLegacyJson(CLUSTERS_DIRECTORY + "c1.json", cluster);
        final AggregatedHttpResponse response =
                createCluster("groups/" + GROUP, "c1", cluster, dogma.httpClient());
        assertAlreadyExists(response);
    }

    @Test
    void updateCluster_migratesLegacyJson() throws IOException {
        pushLegacyJson(CLUSTERS_DIRECTORY + "c2.json", cluster("ignored", 1));
        final String clusterName = "groups/" + GROUP + "/clusters/c2";
        final Cluster updatingCluster = cluster("ignored", 2).toBuilder().setName(clusterName).build();
        assertOk(updateCluster("groups/" + GROUP, "c2", updatingCluster, dogma.httpClient()));
        final Repository repo = repo();
        assertFileExists(repo, CLUSTERS_DIRECTORY + "c2.yaml");
        assertFileAbsent(repo, CLUSTERS_DIRECTORY + "c2.json");
    }

    @Test
    void deleteCluster_removesLegacyJson() throws IOException {
        pushLegacyJson(CLUSTERS_DIRECTORY + "c3.json", cluster("ignored", 1));
        assertOk(deleteResource("/api/v1/xds/groups/" + GROUP + "/clusters/c3"));
        assertFileAbsent(repo(), CLUSTERS_DIRECTORY + "c3.json");
    }

    // ---- Listener ----

    @Test
    void createListener_conflictsWithLegacyJson() throws IOException {
        final Listener listener = exampleListener("ignored", "route-for-l1", "l1");
        pushLegacyJson(LISTENERS_DIRECTORY + "l1.json", listener);
        assertAlreadyExists(createListener("groups/" + GROUP, "l1", listener, dogma.httpClient()));
    }

    @Test
    void updateListener_migratesLegacyJson() throws IOException {
        pushLegacyJson(LISTENERS_DIRECTORY + "l2.json", exampleListener("ignored", "route-for-l2", "l2"));
        final String listenerName = "groups/" + GROUP + "/listeners/l2";
        final Listener updatingListener =
                exampleListener("ignored", "route-for-l2-updated", "l2").toBuilder()
                                                                         .setName(listenerName).build();
        assertOk(updateListener("groups/" + GROUP, "l2", updatingListener, dogma.httpClient()));
        final Repository repo = repo();
        assertFileExists(repo, LISTENERS_DIRECTORY + "l2.yaml");
        assertFileAbsent(repo, LISTENERS_DIRECTORY + "l2.json");
    }

    @Test
    void deleteListener_removesLegacyJson() throws IOException {
        pushLegacyJson(LISTENERS_DIRECTORY + "l3.json", exampleListener("ignored", "route-for-l3", "l3"));
        assertOk(deleteResource("/api/v1/xds/groups/" + GROUP + "/listeners/l3"));
        assertFileAbsent(repo(), LISTENERS_DIRECTORY + "l3.json");
    }

    // ---- Route ----

    @Test
    void createRoute_conflictsWithLegacyJson() throws IOException {
        final RouteConfiguration route = routeConfiguration("ignored", "cluster-r1");
        pushLegacyJson(ROUTES_DIRECTORY + "r1.json", route);
        assertAlreadyExists(createRoute("groups/" + GROUP, "r1", route, dogma.httpClient()));
    }

    @Test
    void updateRoute_migratesLegacyJson() throws IOException {
        pushLegacyJson(ROUTES_DIRECTORY + "r2.json", routeConfiguration("ignored", "cluster-r2"));
        final String routeName = "groups/" + GROUP + "/routes/r2";
        final RouteConfiguration updatingRoute =
                routeConfiguration("ignored", "cluster-r2-updated").toBuilder().setName(routeName).build();
        assertOk(updateRoute("groups/" + GROUP, "r2", updatingRoute, dogma.httpClient()));
        final Repository repo = repo();
        assertFileExists(repo, ROUTES_DIRECTORY + "r2.yaml");
        assertFileAbsent(repo, ROUTES_DIRECTORY + "r2.json");
    }

    @Test
    void deleteRoute_removesLegacyJson() throws IOException {
        pushLegacyJson(ROUTES_DIRECTORY + "r3.json", routeConfiguration("ignored", "cluster-r3"));
        assertOk(deleteResource("/api/v1/xds/groups/" + GROUP + "/routes/r3"));
        assertFileAbsent(repo(), ROUTES_DIRECTORY + "r3.json");
    }

    // ---- Endpoint ----

    @Test
    void createEndpoint_conflictsWithLegacyJson() throws IOException {
        final ClusterLoadAssignment endpoint = loadAssignment("ignored", "127.0.0.1", 8080);
        pushLegacyJson(ENDPOINTS_DIRECTORY + "e1.json", endpoint);
        assertAlreadyExists(createEndpoint("groups/" + GROUP, "e1", endpoint, dogma.httpClient()));
    }

    @Test
    void updateEndpoint_migratesLegacyJson() throws IOException {
        pushLegacyJson(ENDPOINTS_DIRECTORY + "e2.json", loadAssignment("ignored", "127.0.0.1", 8080));
        assertOk(updateEndpoint("e2", loadAssignment("ignored", "127.0.0.1", 9090)));
        final Repository repo = repo();
        assertFileExists(repo, ENDPOINTS_DIRECTORY + "e2.yaml");
        assertFileAbsent(repo, ENDPOINTS_DIRECTORY + "e2.json");
    }

    @Test
    void deleteEndpoint_removesLegacyJson() throws IOException {
        pushLegacyJson(ENDPOINTS_DIRECTORY + "e3.json", loadAssignment("ignored", "127.0.0.1", 8080));
        assertOk(deleteResource("/api/v1/xds/groups/" + GROUP + "/endpoints/e3"));
        assertFileAbsent(repo(), ENDPOINTS_DIRECTORY + "e3.json");
    }

    // ---- Helpers ----

    private static <T extends Message> void pushLegacyJson(String path, T resource) throws IOException {
        final JsonNode jsonNode = Jackson.readTree(JSON_MESSAGE_MARSHALLER.writeValueAsString(resource));
        dogma.client()
             .forRepo(INTERNAL_PROJECT_XDS, GROUP)
             .commit("Add legacy " + path, Change.ofJsonUpsert(path, jsonNode))
             .push()
             .join();
    }

    private static Repository repo() {
        return dogma.projectManager().get(INTERNAL_PROJECT_XDS).repos().get(GROUP);
    }

    private static void assertFileExists(Repository repo, String path) {
        final Query<?> query = path.endsWith(".yaml") ? Query.ofYaml(path) : Query.ofJson(path);
        assertThat(repo.getOrNull(Revision.HEAD, query).join())
                .as("expected file to exist: %s", path)
                .isNotNull();
    }

    private static void assertFileAbsent(Repository repo, String path) {
        final Query<?> query = path.endsWith(".yaml") ? Query.ofYaml(path) : Query.ofJson(path);
        assertThat(repo.getOrNull(Revision.HEAD, query).join())
                .as("expected file to be absent: %s", path)
                .isNull();
    }

    private static AggregatedHttpResponse deleteResource(String path) {
        return dogma.httpClient()
                    .execute(RequestHeaders.builder(HttpMethod.DELETE, path)
                                          .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                                          .build())
                    .aggregate()
                    .join();
    }

    private static AggregatedHttpResponse updateEndpoint(String endpointId,
                                                          ClusterLoadAssignment endpoint) throws IOException {
        return dogma.httpClient()
                    .execute(RequestHeaders.builder(HttpMethod.PUT,
                                                    "/api/v1/xds/groups/" + GROUP + "/endpoints/" +
                                                    endpointId)
                                          .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                                          .contentType(MediaType.parse("application/yaml"))
                                          .build(),
                             XdsTestUtil.toYaml(endpoint))
                    .aggregate()
                    .join();
    }

    private static void assertOk(AggregatedHttpResponse response) {
        assertThat(response.status()).isSameAs(HttpStatus.OK);
    }

    private static void assertAlreadyExists(AggregatedHttpResponse response) {
        assertThat(response.status()).isSameAs(HttpStatus.CONFLICT);
    }
}
