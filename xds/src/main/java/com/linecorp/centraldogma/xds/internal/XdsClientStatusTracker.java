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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.metadata.UserWithAppIdentity;

import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks;
import io.envoyproxy.controlplane.server.exception.RequestException;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

/**
 * Tracks the ACK/NACK state of every xDS client connected to this control plane instance.
 *
 * <p>State is kept in memory and reflects only the clients connected to <em>this</em> replica. Callbacks are
 * invoked on gRPC worker threads, so all mutable state is held in {@link ConcurrentHashMap}s and {@code
 * volatile} fields.
 *
 * <p>This assumes State-of-the-World (SotW) xDS. Delta xDS ({@link #onV3StreamDeltaRequest}) is not tracked
 * yet; clients using delta would not appear here.
 */
final class XdsClientStatusTracker implements DiscoveryServerCallbacks {

    private static final Logger logger = LoggerFactory.getLogger(XdsClientStatusTracker.class);

    enum AckStatus {
        INITIAL,
        ACKED,
        NACKED
    }

    private final Map<Long, StreamState> streams = new ConcurrentHashMap<>();

    @Override
    public void onStreamOpen(long streamId, String typeUrl) throws RequestException {
        streams.put(streamId, new StreamState(System.currentTimeMillis()));
        // The controlplane invokes onStreamClose/onStreamCloseWithError only for a graceful close or an error
        // surfaced through the request stream; an abrupt client disconnect is delivered as a cancellation
        // (onCancelHandler), which does NOT trigger those callbacks and would leak the entry. Tie removal to
        // the end of the underlying gRPC call instead, which covers every termination path. onStreamOpen is
        // invoked synchronously from the service method with the Armeria context mounted.
        final ServiceRequestContext ctx = ServiceRequestContext.currentOrNull();
        if (ctx != null) {
            ctx.log().whenComplete().thenAccept(unused -> streams.remove(streamId));
        }
    }

    @Override
    public void onStreamClose(long streamId, String typeUrl) {
        streams.remove(streamId);
    }

    @Override
    public void onStreamCloseWithError(long streamId, String typeUrl, Throwable error) {
        streams.remove(streamId);
    }

    @Override
    public void onV3StreamRequest(long streamId, DiscoveryRequest request) throws RequestException {
        final StreamState stream = streams.get(streamId);
        if (stream == null) {
            // Should not happen because onStreamOpen always precedes onV3StreamRequest, but be defensive.
            return;
        }
        // The node is populated only on the first request of a stream, so remember it once.
        if (stream.nodeId.isEmpty() && request.hasNode()) {
            stream.nodeId = request.getNode().getId();
            stream.nodeCluster = request.getNode().getCluster();
        }
        // The authenticated application identity (set on the context by the discovery auth decorator) is what
        // the control plane scopes the served snapshot by, so capture it once. It is distinct from the Envoy
        // node id above; it stays empty for anonymous clients served the full (DEFAULT_GROUP) snapshot.
        if (stream.appId.isEmpty()) {
            final User user = AuthUtil.currentUserOrNull();
            if (user instanceof UserWithAppIdentity) {
                stream.appId = user.login();
            }
        }

        // ADS multiplexes multiple type_urls over a single stream, so key the state by type_url.
        final TypeState type = stream.perType.computeIfAbsent(request.getTypeUrl(), unused -> new TypeState());
        type.lastNonce = request.getResponseNonce();
        type.lastSeenMillis = System.currentTimeMillis();
        // Snapshot the subscribed resource names. An empty list means wildcard (subscribe to all).
        // An initial subscribe has both version_info and response_nonce empty; ACK/NACK requests always
        // carry a non-empty nonce. For initial subscribes, always update (even empty = wildcard). For
        // ACK/NACK, skip empty lists: some clients omit resource_names to mean "no change to subscription".
        final List<String> names = request.getResourceNamesList();
        final boolean isInitialSubscribe = request.getVersionInfo().isEmpty() &&
                                           request.getResponseNonce().isEmpty();
        if (isInitialSubscribe || !names.isEmpty()) {
            type.resourceNames = ImmutableList.copyOf(names);
        }
        if (request.hasErrorDetail()) {
            type.status = AckStatus.NACKED;
            type.nackReason = request.getErrorDetail().getMessage();
        } else if (!request.getVersionInfo().isEmpty()) {
            type.status = AckStatus.ACKED;
            type.ackedVersion = request.getVersionInfo();
            type.nackReason = "";
        }
        // Otherwise this is the initial subscribe; keep the INITIAL status.

        logger.debug("Received v3 stream request. streamId: {}, version: {}, resource_names: {}, " +
                     "response_nonce: {}, type_url: {}", streamId, request.getVersionInfo(),
                     request.getResourceNamesList(), request.getResponseNonce(), request.getTypeUrl());
    }

    @Override
    public void onV3StreamDeltaRequest(long streamId, DeltaDiscoveryRequest request) throws RequestException {
        // Delta xDS is not tracked yet; see the class-level note.
    }

    @Override
    public void onV3StreamResponse(long streamId, DiscoveryRequest request, DiscoveryResponse response) {
        final StreamState stream = streams.get(streamId);
        if (stream != null) {
            final TypeState type =
                    stream.perType.computeIfAbsent(response.getTypeUrl(), unused -> new TypeState());
            type.lastSentVersion = response.getVersionInfo();
        }
        logger.debug("Sent v3 stream response. streamId: {}, version: {}, " +
                     "response_nonce: {}, type_url: {}", streamId, response.getVersionInfo(),
                     response.getNonce(), response.getTypeUrl());
    }

    /**
     * Returns the current state of every connected client as a JSON array. Each element describes one stream
     * and the ACK/NACK state of every resource type subscribed on it. The view is eventually consistent: it
     * may observe concurrent updates from the gRPC worker threads.
     */
    ArrayNode toClientsJson() {
        final ArrayNode array = JsonNodeFactory.instance.arrayNode();
        streams.forEach((streamId, stream) -> {
            final ObjectNode streamNode = array.addObject();
            streamNode.put("streamId", streamId);
            streamNode.put("nodeId", stream.nodeId);
            streamNode.put("nodeCluster", stream.nodeCluster);
            streamNode.put("appId", stream.appId);
            streamNode.put("openedAt", stream.openedAtMillis);
            final ArrayNode types = streamNode.putArray("types");
            stream.perType.forEach((typeUrl, type) -> {
                final ObjectNode typeNode = types.addObject();
                final boolean inSync = type.status == AckStatus.ACKED &&
                                       !type.lastSentVersion.isEmpty() &&
                                       type.ackedVersion.equals(type.lastSentVersion);
                typeNode.put("typeUrl", typeUrl);
                typeNode.put("status", type.status.name());
                typeNode.put("ackedVersion", type.ackedVersion);
                typeNode.put("servedVersion", type.lastSentVersion);
                typeNode.put("inSync", inSync);
                typeNode.put("nackReason", type.nackReason);
                typeNode.put("lastNonce", type.lastNonce);
                typeNode.put("lastSeen", type.lastSeenMillis);
                final ArrayNode resourceNames = typeNode.putArray("resourceNames");
                type.resourceNames.forEach(resourceNames::add);
            });
        });
        return array;
    }

    /**
     * The state of a single discovery stream. {@link #nodeId}/{@link #nodeCluster} are mutated only from
     * {@link #onV3StreamRequest} on the owning stream's gRPC thread; {@code volatile} makes the captured node
     * visible to the reader of {@link #toClientsJson()}.
     */
    private static final class StreamState {

        private final long openedAtMillis;
        private final Map<String, TypeState> perType = new ConcurrentHashMap<>();

        private volatile String nodeId = "";
        private volatile String nodeCluster = "";
        private volatile String appId = "";

        StreamState(long openedAtMillis) {
            this.openedAtMillis = openedAtMillis;
        }
    }

    /**
     * The ACK/NACK state of one resource type on a stream.
     */
    private static final class TypeState {

        private volatile AckStatus status = AckStatus.INITIAL;
        private volatile String ackedVersion = "";
        private volatile String lastNonce = "";
        private volatile String nackReason = "";
        private volatile String lastSentVersion = "";
        private volatile long lastSeenMillis;
        // The resource names the client subscribed to. An empty list means wildcard.
        private volatile List<String> resourceNames = ImmutableList.of();
    }
}
