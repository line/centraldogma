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
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.CLUSTERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN_STRING;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.removePrefix;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.protobuf.Empty;

import com.linecorp.centraldogma.xds.endpoint.v1.XdsEndpointServiceGrpc.XdsEndpointServiceImplBase;
import com.linecorp.centraldogma.xds.internal.XdsResourceManager;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
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
        xdsResourceManager.push(responseObserver, group, fileName(endpointId),
                                "Create endpoint: " + clusterName, endpoint, currentAuthor());
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

        final ClusterLoadAssignment endpoint = request.getEndpoint();
        final String endpointId = matcher.group(2);
        xdsResourceManager.update(responseObserver, group, endpointName,
                                  fileName(endpointId), "Update endpoint: " + endpointName,
                                  endpoint.toBuilder()
                                          .setClusterName(clusterName("groups/" + group, endpointId))
                                          .build());
    }

    @Override
    public void deleteEndpoint(DeleteEndpointRequest request, StreamObserver<Empty> responseObserver) {
        final String endpointName = request.getName();
        final Matcher matcher = checkEndpointName(endpointName);
        final String group = matcher.group(1);
        xdsResourceManager.delete(responseObserver, group, endpointName,
                                  fileName(matcher.group(2)), "Delete endpoint: " + endpointName);
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
}
