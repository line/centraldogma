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

package com.linecorp.centraldogma.xds;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import com.google.rpc.Status;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.HttpHeaderNames;

import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.stub.StreamObserver;

/**
 * A tiny standalone xDS client, mirroring the streaming pattern of {@code CdsStreamingTest}, that connects to a
 * running control plane over a single ADS (aggregated) stream, subscribes to CDS and LDS, and ACKs every
 * response. It then stays connected so the connected client appears in the system-administrator "xDS Control
 * Plane" view (and {@code GET /api/v1/xds/clients}).
 *
 * <p>Only CDS and LDS are subscribed, with no resource names (wildcard), exactly like a real Envoy ADS
 * bootstrap ({@code ads.config.yaml}). In ADS the cache expects the CDS response to name the EDS clusters and
 * the LDS response to name the RDS routes, so EDS/RDS are requested by name on demand rather than wildcard;
 * this demo client does not act on the resources, so it does not follow those references.
 *
 * <p>The discovery gRPC service uses optional authentication, so this anonymous client is served the full
 * (DEFAULT_GROUP) snapshot without any token.
 *
 * <p>Pass an application access token as the 4th argument to authenticate as an application identity; the
 * control plane then scopes the served snapshot to the groups that identity can read (an anonymous or
 * system-admin client is served the full DEFAULT_GROUP snapshot instead). This is what populates the per
 * application-identity view.
 *
 * <p>Pass the literal {@code nack} (in any argument position) to reject every response instead of ACKing it,
 * i.e. send a {@code DiscoveryRequest} with {@code error_detail} set. A real Envoy keeps the stream open while
 * NACKing, so this lets the NACKED status and reason be seen in the client status view without a real Envoy.
 *
 * <p>Run against a server started with {@code ./gradlew :xds:runXdsTestServer}:
 * <pre>{@code
 * ./gradlew :xds:runXdsTestClient
 * ./gradlew :xds:runXdsTestClient --args="http://127.0.0.1:36462 my-node my-cluster"
 * ./gradlew :xds:runXdsTestClient --args="http://127.0.0.1:36462 my-node my-cluster <app-token>"
 * ./gradlew :xds:runXdsTestClient --args="http://127.0.0.1:36462 my-node my-cluster nack"
 * }</pre>
 */
public final class XdsTestClient {

    // The resource types subscribed (wildcard) over the single ADS stream, matching a real Envoy ADS bootstrap.
    // Each becomes a row in the client status view.
    private static final String[] TYPE_URLS = {
            Resources.V3.CLUSTER_TYPE_URL,
            Resources.V3.LISTENER_TYPE_URL,
    };

    // In nack mode, wait this long before sending each NACK so the exchange is easy to observe.
    private static final long NACK_DELAY_MILLIS = 1000;

    @SuppressWarnings("UncommentedMain")
    public static void main(String[] args) throws Exception {
        // 'nack' may appear in any position; everything else is positional (uri, nodeId, nodeCluster, token).
        final List<String> positional = new ArrayList<>();
        boolean nackMode = false;
        for (String arg : args) {
            if ("nack".equalsIgnoreCase(arg)) {
                nackMode = true;
            } else {
                positional.add(arg);
            }
        }
        final String uri = positional.size() > 0 ? positional.get(0) : "http://127.0.0.1:36462";
        final String nodeId = positional.size() > 1 ? positional.get(1) : "test-id";
        final String nodeCluster = positional.size() > 2 ? positional.get(2) : "test-cluster";
        final String token = positional.size() > 3 ? positional.get(3) : null;
        final Node node = Node.newBuilder().setId(nodeId).setCluster(nodeCluster).build();

        System.out.printf("Connecting ADS client to %s (node id=%s, cluster=%s, authenticated=%b, nack=%b)%n",
                          uri, nodeId, nodeCluster, token != null, nackMode);
        final GrpcClientBuilder builder =
                GrpcClients.builder(uri)
                           // This is a long-lived discovery stream, so disable the client timeouts and the
                           // response length limit (0 means unlimited); otherwise the default response timeout
                           // would abort the stream after a few seconds.
                           .responseTimeoutMillis(0)
                           .writeTimeoutMillis(0)
                           .maxResponseLength(0);
        // When a token is supplied, authenticate as an application identity so the control plane serves an
        // app-scoped snapshot; otherwise connect anonymously and receive the full snapshot.
        if (token != null) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
        }
        final AggregatedDiscoveryServiceStub stub = builder.build(AggregatedDiscoveryServiceStub.class);

        // Responses are queued here and ACKed from a dedicated thread, so we never send a request re-entrantly
        // from within the response callback (mirroring CdsStreamingTest, which ACKs from the main thread).
        final BlockingQueue<DiscoveryResponse> responses = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final StreamObserver<DiscoveryRequest> requests = stub.streamAggregatedResources(
                new StreamObserver<>() {
                    @Override
                    public void onNext(DiscoveryResponse response) {
                        responses.add(response);
                    }

                    @Override
                    public void onError(Throwable t) {
                        System.err.println("Stream error: " + t);
                        closed.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        System.out.println("Stream completed by server.");
                        closed.countDown();
                    }
                });

        // Initial subscribe (version_info and response_nonce empty) for every type on the single ADS stream.
        for (String typeUrl : TYPE_URLS) {
            requests.onNext(DiscoveryRequest.newBuilder().setNode(node).setTypeUrl(typeUrl).build());
        }

        // Respond to responses from a single dedicated thread; it is the only thread that sends requests after
        // the initial subscribes above, so the request stream is never written concurrently.
        final boolean nack = nackMode;
        final Thread acker = new Thread(() -> {
            try {
                while (true) {
                    final DiscoveryResponse response = responses.take();
                    System.out.printf("<= response  type=%s version=%s nonce=%s resources=%d%n",
                                      shortType(response.getTypeUrl()), response.getVersionInfo(),
                                      response.getNonce(), response.getResourcesCount());
                    final DiscoveryRequest.Builder ack =
                            DiscoveryRequest.newBuilder()
                                            .setNode(node)
                                            .setTypeUrl(response.getTypeUrl())
                                            .setResponseNonce(response.getNonce());
                    if (nack) {
                        // Pace the NACKs so they are easy to observe.
                        Thread.sleep(NACK_DELAY_MILLIS);
                        // NACK: echo the rejected version in version_info and set error_detail. Echoing the
                        // version (rather than leaving it empty) makes the control plane open a watch and wait
                        // for the next change instead of immediately re-sending the same version, which would
                        // otherwise produce a tight NACK storm. error_detail still marks this as a NACK, so the
                        // NACKED status and reason stay visible in the client status view.
                        ack.setVersionInfo(response.getVersionInfo())
                           .setErrorDetail(Status.newBuilder().setMessage(
                                   "rejected by XdsTestClient (nack mode): simulated configuration rejection"));
                        requests.onNext(ack.build());
                        System.out.printf("=> NACK      type=%s version=%s nonce=%s%n",
                                          shortType(response.getTypeUrl()), response.getVersionInfo(),
                                          response.getNonce());
                    } else {
                        // ACK: echo the response's version_info and nonce, with no error_detail.
                        ack.setVersionInfo(response.getVersionInfo());
                        requests.onNext(ack.build());
                        System.out.printf("=> ACK       type=%s version=%s%n",
                                          shortType(response.getTypeUrl()), response.getVersionInfo());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ads-acker");
        acker.setDaemon(true);
        acker.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                requests.onCompleted();
            } catch (Exception ignored) {
                // The stream may already be closed.
            }
        }));

        System.out.println("Subscribed to CDS/LDS. Staying connected; press Ctrl+C to disconnect.");
        // Block until the server closes the stream or the process is interrupted, keeping the client connected
        // so it shows up in the control plane client status view.
        closed.await();
        acker.interrupt();
    }

    private static String shortType(String typeUrl) {
        final int dot = typeUrl.lastIndexOf('.');
        return dot >= 0 ? typeUrl.substring(dot + 1) : typeUrl;
    }

    private XdsTestClient() {}
}
