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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.rpc.Status;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

class XdsClientStatusTrackerTest {

    private static final String CDS_TYPE_URL = "type.googleapis.com/envoy.config.cluster.v3.Cluster";

    @Test
    void initialSubscribeIsNotAnAck() throws Exception {
        final XdsClientStatusTracker tracker = new XdsClientStatusTracker();
        tracker.onStreamOpen(1, CDS_TYPE_URL);
        // The very first request has empty version_info and response_nonce, and carries the node.
        tracker.onV3StreamRequest(1, DiscoveryRequest.newBuilder()
                                                     .setNode(Node.newBuilder()
                                                                  .setId("node-1")
                                                                  .setCluster("cluster-a"))
                                                     .setTypeUrl(CDS_TYPE_URL)
                                                     .build());

        final JsonNode type = singleType(tracker);
        assertThat(type.get("status").asText()).isEqualTo("INITIAL");
        assertThat(type.get("ackedVersion").asText()).isEmpty();
        assertThat(type.get("inSync").asBoolean()).isFalse();

        final JsonNode stream = tracker.toClientsJson().get(0);
        assertThat(stream.get("nodeId").asText()).isEqualTo("node-1");
        assertThat(stream.get("nodeCluster").asText()).isEqualTo("cluster-a");
    }

    @Test
    void ackOfTheServedVersionIsInSync() throws Exception {
        final XdsClientStatusTracker tracker = new XdsClientStatusTracker();
        tracker.onStreamOpen(1, CDS_TYPE_URL);
        // The server sent version v1...
        tracker.onV3StreamResponse(1, DiscoveryRequest.getDefaultInstance(),
                                   DiscoveryResponse.newBuilder()
                                                    .setTypeUrl(CDS_TYPE_URL)
                                                    .setVersionInfo("v1")
                                                    .setNonce("nonce-1")
                                                    .build());
        // ...and the client ACKs v1 (no error_detail, non-empty version_info).
        tracker.onV3StreamRequest(1, DiscoveryRequest.newBuilder()
                                                     .setTypeUrl(CDS_TYPE_URL)
                                                     .setVersionInfo("v1")
                                                     .setResponseNonce("nonce-1")
                                                     .build());

        final JsonNode type = singleType(tracker);
        assertThat(type.get("status").asText()).isEqualTo("ACKED");
        assertThat(type.get("ackedVersion").asText()).isEqualTo("v1");
        assertThat(type.get("servedVersion").asText()).isEqualTo("v1");
        assertThat(type.get("inSync").asBoolean()).isTrue();
    }

    @Test
    void ackOfAnOlderVersionIsOutOfSync() throws Exception {
        final XdsClientStatusTracker tracker = new XdsClientStatusTracker();
        tracker.onStreamOpen(1, CDS_TYPE_URL);
        tracker.onV3StreamResponse(1, DiscoveryRequest.getDefaultInstance(),
                                   DiscoveryResponse.newBuilder()
                                                    .setTypeUrl(CDS_TYPE_URL)
                                                    .setVersionInfo("v2")
                                                    .build());
        tracker.onV3StreamRequest(1, DiscoveryRequest.newBuilder()
                                                     .setTypeUrl(CDS_TYPE_URL)
                                                     .setVersionInfo("v1")
                                                     .build());

        final JsonNode type = singleType(tracker);
        assertThat(type.get("status").asText()).isEqualTo("ACKED");
        assertThat(type.get("ackedVersion").asText()).isEqualTo("v1");
        assertThat(type.get("servedVersion").asText()).isEqualTo("v2");
        assertThat(type.get("inSync").asBoolean()).isFalse();
    }

    @Test
    void errorDetailIsANack() throws Exception {
        final XdsClientStatusTracker tracker = new XdsClientStatusTracker();
        tracker.onStreamOpen(1, CDS_TYPE_URL);
        final Status error = Status.newBuilder().setMessage("bad config").build();
        tracker.onV3StreamRequest(1, DiscoveryRequest.newBuilder()
                                                     .setTypeUrl(CDS_TYPE_URL)
                                                     .setVersionInfo("v1")
                                                     .setErrorDetail(error)
                                                     .build());

        final JsonNode type = singleType(tracker);
        assertThat(type.get("status").asText()).isEqualTo("NACKED");
        assertThat(type.get("nackReason").asText()).isEqualTo("bad config");
        assertThat(type.get("inSync").asBoolean()).isFalse();
    }

    @Test
    void streamCloseRemovesTheClient() throws Exception {
        final XdsClientStatusTracker tracker = new XdsClientStatusTracker();
        tracker.onStreamOpen(1, CDS_TYPE_URL);
        tracker.onV3StreamRequest(1, DiscoveryRequest.newBuilder().setTypeUrl(CDS_TYPE_URL).build());
        assertThat(tracker.toClientsJson()).hasSize(1);

        tracker.onStreamClose(1, CDS_TYPE_URL);
        assertThat(tracker.toClientsJson()).isEmpty();
    }

    // Returns the single resource-type node of the single tracked stream.
    private static JsonNode singleType(XdsClientStatusTracker tracker) {
        final ArrayNode clients = tracker.toClientsJson();
        assertThat(clients).hasSize(1);
        final JsonNode types = clients.get(0).get("types");
        assertThat(types).hasSize(1);
        return types.get(0);
    }
}
