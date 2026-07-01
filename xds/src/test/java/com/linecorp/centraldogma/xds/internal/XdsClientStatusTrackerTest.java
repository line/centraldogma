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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.rpc.Status;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

class XdsClientStatusTrackerTest {

    private static final String CDS_TYPE_URL = "type.googleapis.com/envoy.config.cluster.v3.Cluster";

    @Test
    void initialSubscribeIsNotAnAck() throws Exception {
        final XdsClientStatusTracker tracker = new XdsClientStatusTracker();
        openStream(tracker, 1);
        // The very first request has empty version_info and response_nonce, and carries the node.
        tracker.onV3StreamRequest(1, DiscoveryRequest.newBuilder()
                                                     .setNode(Node.newBuilder()
                                                                  .setId("node-1")
                                                                  .setCluster("cluster-a"))
                                                     .setTypeUrl(CDS_TYPE_URL)
                                                     .build());

        final XdsTypeStateDto type = singleType(tracker);
        assertThat(type.status()).isEqualTo("INITIAL");
        assertThat(type.ackedVersion()).isEmpty();

        final XdsClientStreamDto stream = tracker.clients().get(0);
        assertThat(stream.nodeId()).isEqualTo("node-1");
        assertThat(stream.nodeCluster()).isEqualTo("cluster-a");
    }

    @Test
    void ackOfTheServedVersionIsInSync() throws Exception {
        final XdsClientStatusTracker tracker = new XdsClientStatusTracker();
        openStream(tracker, 1);
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

        final XdsTypeStateDto type = singleType(tracker);
        assertThat(type.status()).isEqualTo("ACKED");
        assertThat(type.ackedVersion()).isEqualTo("v1");
        assertThat(type.servedVersion()).isEqualTo("v1");
    }

    @Test
    void ackOfAnOlderVersionIsOutOfSync() throws Exception {
        final XdsClientStatusTracker tracker = new XdsClientStatusTracker();
        openStream(tracker, 1);
        tracker.onV3StreamResponse(1, DiscoveryRequest.getDefaultInstance(),
                                   DiscoveryResponse.newBuilder()
                                                    .setTypeUrl(CDS_TYPE_URL)
                                                    .setVersionInfo("v2")
                                                    .build());
        tracker.onV3StreamRequest(1, DiscoveryRequest.newBuilder()
                                                     .setTypeUrl(CDS_TYPE_URL)
                                                     .setVersionInfo("v1")
                                                     .build());

        final XdsTypeStateDto type = singleType(tracker);
        assertThat(type.status()).isEqualTo("ACKED");
        assertThat(type.ackedVersion()).isEqualTo("v1");
        assertThat(type.servedVersion()).isEqualTo("v2");
    }

    @Test
    void errorDetailIsANack() throws Exception {
        final XdsClientStatusTracker tracker = new XdsClientStatusTracker();
        openStream(tracker, 1);
        final Status error = Status.newBuilder().setMessage("bad config").build();
        tracker.onV3StreamRequest(1, DiscoveryRequest.newBuilder()
                                                     .setTypeUrl(CDS_TYPE_URL)
                                                     .setVersionInfo("v1")
                                                     .setErrorDetail(error)
                                                     .build());

        final XdsTypeStateDto type = singleType(tracker);
        assertThat(type.status()).isEqualTo("NACKED");
        assertThat(type.nackReason()).isEqualTo("bad config");
    }

    @Test
    void streamCloseRemovesTheClient() throws Exception {
        final XdsClientStatusTracker tracker = new XdsClientStatusTracker();
        openStream(tracker, 1);
        tracker.onV3StreamRequest(1, DiscoveryRequest.newBuilder().setTypeUrl(CDS_TYPE_URL).build());
        assertThat(tracker.clients()).hasSize(1);

        tracker.onStreamClose(1, CDS_TYPE_URL);
        assertThat(tracker.clients()).isEmpty();
    }

    // Calls onStreamOpen with a real Armeria context so that the assert ctx != null in the
    // production code is satisfied (Gradle runs tests with assertions enabled by default).
    private static void openStream(XdsClientStatusTracker tracker, long streamId) throws Exception {
        final ServiceRequestContext ctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        try (SafeCloseable ignored = ctx.push()) {
            tracker.onStreamOpen(streamId, CDS_TYPE_URL);
        }
    }

    // Returns the single resource-type state of the single tracked stream.
    private static XdsTypeStateDto singleType(XdsClientStatusTracker tracker) {
        final List<XdsClientStreamDto> clients = tracker.clients();
        assertThat(clients).hasSize(1);
        final List<XdsTypeStateDto> types = clients.get(0).types();
        assertThat(types).hasSize(1);
        return types.get(0);
    }
}
