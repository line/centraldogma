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
package com.linecorp.centraldogma.xds.endpoint.v1;

import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.CLUSTERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN_STRING;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.removePrefix;

import java.io.IOException;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Empty;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.ContentTransformer;
import com.linecorp.centraldogma.xds.endpoint.v1.XdsEndpointServiceGrpc.XdsEndpointServiceImplBase;
import com.linecorp.centraldogma.xds.internal.XdsResourceManager;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Builder;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Service for managing endpoints.
 */
public final class XdsEndpointService extends XdsEndpointServiceImplBase {

    private static final Pattern ENDPONT_NAME_PATTERN =
            Pattern.compile("^groups/([^/]+)/endpoints/(" + RESOURCE_ID_PATTERN_STRING + ")$");

    private final XdsResourceManager xdsResourceManager;

    /**
     * Creates a new instance.
     */
    public XdsEndpointService(XdsResourceManager xdsResourceManager) {
        this.xdsResourceManager = xdsResourceManager;
    }

    @Override
    public void createEndpoint(CreateEndpointRequest request,
                               StreamObserver<ClusterLoadAssignment> responseObserver) {
        final String parent = request.getParent();
        final String group = removePrefix("groups/", parent);
        xdsResourceManager.checkGroup(group);

        final String endpointId = request.getEndpointId();
        if (!RESOURCE_ID_PATTERN.matcher(endpointId).matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid endpoint_id: " + endpointId +
                                                          " (expected: " + RESOURCE_ID_PATTERN + ')')
                                         .asRuntimeException();
        }

        final String clusterName = clusterName(parent, endpointId);
        // Ignore the specified name in the endpoint and set the name
        // with the format of "groups/{group}/clusters/{endpoint}".
        // https://github.com/aip-dev/google.aip.dev/blob/master/aip/general/0133.md#user-specified-ids
        final ClusterLoadAssignment endpoint = request.getEndpoint()
                                                      .toBuilder()
                                                      .setClusterName(clusterName)
                                                      .build();
        xdsResourceManager.push(responseObserver, group, clusterName, fileName(endpointId),
                                "Create endpoint: " + clusterName, endpoint, currentAuthor(), true);
    }

    private static String clusterName(String parent, String endpointId) {
        // Use /clusters/ instead of /endpoints/ for the cluster name.
        // /endpoints/ will be used for the file name.
        return parent + CLUSTERS_DIRECTORY + endpointId;
    }

    @Override
    public void updateEndpoint(UpdateEndpointRequest request,
                               StreamObserver<ClusterLoadAssignment> responseObserver) {
        final String endpointName = request.getEndpointName();
        final Matcher matcher = checkEndpointName(endpointName);
        final String group = matcher.group(1);
        xdsResourceManager.checkGroup(group);

        final ClusterLoadAssignment endpoint = request.getEndpoint();
        final String endpointId = matcher.group(2);
        xdsResourceManager.update(responseObserver, group, endpointName,
                                  fileName(endpointId), "Update endpoint: " + endpointName,
                                  endpoint.toBuilder()
                                          .setClusterName(clusterName("groups/" + group, endpointId))
                                          .build(), currentAuthor());
    }

    @Override
    public void deleteEndpoint(DeleteEndpointRequest request, StreamObserver<Empty> responseObserver) {
        final String endpointName = request.getName();
        final Matcher matcher = checkEndpointName(endpointName);
        final String group = matcher.group(1);
        xdsResourceManager.checkGroup(group);
        xdsResourceManager.delete(responseObserver, group, endpointName, fileName(matcher.group(2)),
                                  "Delete endpoint: " + endpointName, currentAuthor());
    }

    private static Matcher checkEndpointName(String endpointName) {
        final Matcher matcher = ENDPONT_NAME_PATTERN.matcher(endpointName);
        if (!matcher.matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid endpoint name: " + endpointName +
                                                          " (expected: " + ENDPONT_NAME_PATTERN + ')')
                                         .asRuntimeException();
        }
        return matcher;
    }

    private static String fileName(String endpointId) {
        return ENDPOINTS_DIRECTORY + endpointId + ".json";
    }

    @Override
    public void registerLocalityLbEndpoint(RegisterLocalityLbEndpointRequest request,
                                           StreamObserver<LocalityLbEndpoint> responseObserver) {
        final LocalityLbEndpoint localityLbEndpoint = request.getLocalityLbEndpoint();
        final String endpointName = request.getEndpointName();
        handleRegisterOrDeregister(
                endpointName,
                localityLbEndpoint,
                localityLbEndpoint,
                responseObserver,
                "Register locality LB endpoint to " + endpointName + ". endpoint: " + localityLbEndpoint,
                true,
                cause -> {
                    if (cause instanceof EntryNotFoundException) {
                        responseObserver.onError(
                                Status.NOT_FOUND.withDescription(
                                              "Endpoint does not exist: " + endpointName)
                                                .asRuntimeException());
                    } else {
                        responseObserver.onError(
                                Status.INTERNAL.withCause(cause).asRuntimeException());
                    }
                }
        );
    }

    @Override
    public void deregisterLocalityLbEndpoint(DeregisterLocalityLbEndpointRequest request,
                                             StreamObserver<Empty> responseObserver) {
        final String endpointName = request.getEndpointName();
        final LocalityLbEndpoint localityLbEndpoint = request.getLocalityLbEndpoint();
        handleRegisterOrDeregister(
                endpointName,
                localityLbEndpoint,
                Empty.getDefaultInstance(),
                responseObserver,
                "Deregister locality LB endpoint from " + endpointName + ". endpoint: " + localityLbEndpoint,
                false,
                cause -> {
                    if (cause instanceof LocalityLbEndpointNotFoundException) {
                        responseObserver.onError(
                                Status.NOT_FOUND.withDescription(
                                              "Locality LB endpoint does not exist: " +
                                              localityLbEndpoint)
                                                .asRuntimeException());
                    } else if (cause instanceof EntryNotFoundException) {
                        responseObserver.onError(
                                Status.NOT_FOUND.withDescription(
                                              "Endpoint does not exist: " + endpointName)
                                                .asRuntimeException());
                    } else {
                        responseObserver.onError(
                                Status.INTERNAL.withCause(cause).asRuntimeException());
                    }
                }
        );
    }

    private <T> void handleRegisterOrDeregister(String endpointName,
                                                LocalityLbEndpoint localityLbEndpoint,
                                                T successResponse,
                                                StreamObserver<T> responseObserver,
                                                String commitMessage,
                                                boolean register,
                                                Consumer<Throwable> errorConsumer) {
        final Matcher matcher = checkEndpointName(endpointName);
        final String group = matcher.group(1);
        xdsResourceManager.checkGroup(group);

        final String endpointId = matcher.group(2);
        final String fileName = fileName(endpointId);
        final Author author = currentAuthor();

        final ContentTransformer<JsonNode> transformer = new ContentTransformer<>(
                fileName, EntryType.JSON, new RegisterOrDeregisterTransformer(localityLbEndpoint, register));

        xdsResourceManager.updateOrDelete(
                responseObserver, group, endpointName, fileName, () -> xdsResourceManager
                        .commandExecutor()
                        .execute(Command.transform(
                                null, author, XDS_CENTRAL_DOGMA_PROJECT, group, Revision.HEAD,
                                commitMessage, "", Markup.PLAINTEXT, transformer))
                        .handle((result, cause) -> {
                            if (cause != null) {
                                final Throwable peeled = Exceptions.peel(cause);
                                errorConsumer.accept(peeled);
                                return null;
                            }
                            responseObserver.onNext(successResponse);
                            responseObserver.onCompleted();
                            return null;
                        }));
    }

    private static class RegisterOrDeregisterTransformer implements BiFunction<Revision, JsonNode, JsonNode> {

        final LocalityLbEndpoint localityLbEndpoint;
        private final boolean register;

        RegisterOrDeregisterTransformer(LocalityLbEndpoint localityLbEndpoint, boolean register) {
            this.localityLbEndpoint = localityLbEndpoint;
            this.register = register;
        }

        @Override
        public JsonNode apply(Revision revision, JsonNode oldJsonNode) {
            if (oldJsonNode.isNull()) {
                throw new EntryNotFoundException();
            }
            final ClusterLoadAssignment.Builder clusterLoadAssignmentBuilder =
                    toClusterLoadAssignmentBuilder(oldJsonNode);
            final int localityIndex =
                    findLocalityAndPriorityIndex(clusterLoadAssignmentBuilder, localityLbEndpoint);
            if (localityIndex < 0) {
                if (register) {
                    clusterLoadAssignmentBuilder.addEndpoints(
                            LocalityLbEndpoints.newBuilder()
                                               .setLocality(localityLbEndpoint.getLocality())
                                               .setPriority(localityLbEndpoint.getPriority())
                                               .addLbEndpoints(localityLbEndpoint.getLbEndpoint())
                                               .build());
                    return toJsonNode(clusterLoadAssignmentBuilder);
                }
                throw LocalityLbEndpointNotFoundException.INSTANCE;
            }
            final LocalityLbEndpoints targetLocalityLbEndpoints =
                    clusterLoadAssignmentBuilder.getEndpoints(localityIndex);
            // Remove from the endpoints. It will be added again.
            clusterLoadAssignmentBuilder.removeEndpoints(localityIndex);

            final int lbEndpointIndex =
                    findLbEndpointIndex(targetLocalityLbEndpoints, localityLbEndpoint);
            final LocalityLbEndpoints.Builder targetLocalityLbEndpointsBuilder =
                    targetLocalityLbEndpoints.toBuilder();
            if (register) {
                if (lbEndpointIndex >= 0) {
                    // When the endpoint whose address is the same as the registering endpoint exists,
                    // remove it and add the registering endpoint because the other fields may be different.
                    targetLocalityLbEndpointsBuilder.removeLbEndpoints(lbEndpointIndex);
                }
                targetLocalityLbEndpointsBuilder.addLbEndpoints(localityLbEndpoint.getLbEndpoint());
                clusterLoadAssignmentBuilder.addEndpoints(targetLocalityLbEndpointsBuilder.build());
            } else {
                if (lbEndpointIndex < 0) {
                    throw LocalityLbEndpointNotFoundException.INSTANCE;
                }
                targetLocalityLbEndpointsBuilder.removeLbEndpoints(lbEndpointIndex);
                if (targetLocalityLbEndpointsBuilder.getLbEndpointsCount() > 0) {
                    clusterLoadAssignmentBuilder.addEndpoints(targetLocalityLbEndpointsBuilder.build());
                }
            }
            return toJsonNode(clusterLoadAssignmentBuilder);
        }

        private static int findLbEndpointIndex(LocalityLbEndpoints targetLocalityLbEndpoints,
                                               LocalityLbEndpoint localityLbEndpoint) {
            int sameLbEndpointIndex = -1;
            final List<LbEndpoint> lbEndpointsList = targetLocalityLbEndpoints.getLbEndpointsList();
            for (int i = 0; i < lbEndpointsList.size(); i++) {
                final LbEndpoint lbEndpoint = lbEndpointsList.get(i);
                if (lbEndpoint.getEndpoint().getAddress().equals(
                        localityLbEndpoint.getLbEndpoint().getEndpoint().getAddress())) {
                    sameLbEndpointIndex = i;
                    break;
                }
            }
            return sameLbEndpointIndex;
        }

        /**
         * Find the index of the {@link LocalityLbEndpoints} that has the same locality and priority as the
         * specified {@link LocalityLbEndpoint}.
         * If the locality and priority are not found, return -1.
         */
        private static int findLocalityAndPriorityIndex(Builder clusterLoadAssignmentBuilder,
                                                        LocalityLbEndpoint localityLbEndpoint) {
            int sameLocalityIndex = -1;

            final List<LocalityLbEndpoints> localityLbEndpointsList =
                    clusterLoadAssignmentBuilder.getEndpointsList();
            for (int i = 0; i < localityLbEndpointsList.size(); i++) {
                final LocalityLbEndpoints localityLbEndpoints = localityLbEndpointsList.get(i);
                if (localityLbEndpoints.getLocality().equals(localityLbEndpoint.getLocality()) &&
                    localityLbEndpoints.getPriority() == localityLbEndpoint.getPriority()) {
                    sameLocalityIndex = i;
                    break;
                }
            }
            return sameLocalityIndex;
        }

        private static ClusterLoadAssignment.Builder toClusterLoadAssignmentBuilder(JsonNode oldJsonNode) {
            final Builder clusterLoadAssignmentBuilder =
                    ClusterLoadAssignment.newBuilder();
            try {
                JSON_MESSAGE_MARSHALLER.mergeValue(Jackson.writeValueAsString(oldJsonNode),
                                                   clusterLoadAssignmentBuilder);
            } catch (Throwable t) {
                // Should never reach here.
                throw new Error();
            }
            return clusterLoadAssignmentBuilder;
        }

        private static JsonNode toJsonNode(ClusterLoadAssignment.Builder clusterLoadAssignmentBuilder) {
            try {
                return Jackson.readTree(JSON_MESSAGE_MARSHALLER.writeValueAsString(
                        clusterLoadAssignmentBuilder.build()));
            } catch (IOException e) {
                // Should never reach here
                throw new Error(e);
            }
        }
    }

    private static final class LocalityLbEndpointNotFoundException extends EntryNotFoundException {

        private static final long serialVersionUID = -4661118144903218907L;

        static final LocalityLbEndpointNotFoundException INSTANCE = new LocalityLbEndpointNotFoundException();
    }
}
