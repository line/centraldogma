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

import static com.linecorp.centraldogma.server.storage.project.InternalProjectConstants.INTERNAL_PROJECT_XDS;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.CLUSTERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.K8S_ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.LISTENERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.ROUTES_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService.K8S_ENDPOINT_AGGREGATORS_DIRECTORY;

import java.io.IOException;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Message;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.MirrorException;
import com.linecorp.centraldogma.server.mirror.MirrorFileValidator;
import com.linecorp.centraldogma.xds.k8s.v1.KubernetesEndpointAggregator;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

/**
 * Validates xDS resource files before they are mirrored into an {@code @xds} repository.
 *
 * <p>Each file path determines the expected xDS resource type. Files at unrecognized paths or
 * paths reserved for system-managed resources (e.g. {@code /k8s/endpoints/}) cause an error.
 * The content must be parseable as the expected protobuf message type without unknown fields.</p>
 */
public final class XdsMirrorFileValidator implements MirrorFileValidator {

    @Override
    public void validate(String projectName, String repoName, Change<?> change) {
        if (!INTERNAL_PROJECT_XDS.equals(projectName)) {
            return;
        }
        if (change.type() == ChangeType.REMOVE) {
            return;
        }

        final String filePath = change.path();

        // /k8s/endpoints/ files are written by the Kubernetes controller, not by users.
        if (filePath.startsWith(K8S_ENDPOINTS_DIRECTORY)) {
            throw new MirrorException(
                    filePath + ": files under " + K8S_ENDPOINTS_DIRECTORY +
                    " are managed by the Kubernetes controller and cannot be created via mirroring");
        }

        final Message.Builder builder = builderForPath(filePath);
        if (builder == null) {
            throw new MirrorException(
                    filePath + ": unexpected file path in an xDS repository; " +
                    "only " + CLUSTERS_DIRECTORY + ", " + LISTENERS_DIRECTORY + ", " +
                    ROUTES_DIRECTORY + ", " + ENDPOINTS_DIRECTORY + ", and " +
                    K8S_ENDPOINT_AGGREGATORS_DIRECTORY + " are allowed");
        }

        try {
            final Object content = change.content();
            if (content instanceof JsonNode) {
                // Use JsonNode.traverse() so both JSON and YAML changes are handled uniformly:
                // YAML is already parsed into a JsonNode, and traverse() produces JSON tokens.
                JSON_MESSAGE_MARSHALLER.mergeValue(((JsonNode) content).traverse(), builder);
            } else {
                JSON_MESSAGE_MARSHALLER.mergeValue(change.contentAsText(), builder);
            }
        } catch (IOException e) {
            throw new MirrorException(
                    filePath + ": not a valid " + builder.getDescriptorForType().getName(), e);
        }
    }

    private static Message.@Nullable Builder builderForPath(String filePath) {
        if (filePath.startsWith(CLUSTERS_DIRECTORY)) {
            return Cluster.newBuilder();
        }
        if (filePath.startsWith(LISTENERS_DIRECTORY)) {
            return Listener.newBuilder();
        }
        if (filePath.startsWith(ROUTES_DIRECTORY)) {
            return RouteConfiguration.newBuilder();
        }
        if (filePath.startsWith(ENDPOINTS_DIRECTORY)) {
            return ClusterLoadAssignment.newBuilder();
        }
        if (filePath.startsWith(K8S_ENDPOINT_AGGREGATORS_DIRECTORY)) {
            return KubernetesEndpointAggregator.newBuilder();
        }
        return null;
    }
}
