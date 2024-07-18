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
package com.linecorp.centraldogma.xds.cluster.v1;

import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.CLUSTERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.XdsServiceUtil.RESOURCE_ID_PATTERN;
import static com.linecorp.centraldogma.xds.internal.XdsServiceUtil.RESOURCE_ID_PATTERN_STRING;
import static com.linecorp.centraldogma.xds.internal.XdsServiceUtil.checkGroup;
import static com.linecorp.centraldogma.xds.internal.XdsServiceUtil.delete;
import static com.linecorp.centraldogma.xds.internal.XdsServiceUtil.push;
import static com.linecorp.centraldogma.xds.internal.XdsServiceUtil.removePrefix;
import static com.linecorp.centraldogma.xds.internal.XdsServiceUtil.update;
import static java.util.Objects.requireNonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.protobuf.Empty;

import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.xds.cluster.v1.XdsClusterServiceGrpc.XdsClusterServiceImplBase;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Service for managing clusters.
 */
public final class XdsClusterService extends XdsClusterServiceImplBase {

    private static final Pattern CLUSTER_NAME_PATTERN =
            Pattern.compile("^groups/([^/]+)/clusters/(" + RESOURCE_ID_PATTERN_STRING + ")$");

    private final Project xdsCentralDogmaProject;
    private final CommandExecutor commandExecutor;

    /**
     * Creates a new instance.
     */
    public XdsClusterService(ProjectManager projectManager, CommandExecutor commandExecutor) {
        xdsCentralDogmaProject = requireNonNull(projectManager, "projectManager")
                .get(XDS_CENTRAL_DOGMA_PROJECT);
        this.commandExecutor = requireNonNull(commandExecutor, "commandExecutor");
    }

    @Override
    public void createCluster(CreateClusterRequest request, StreamObserver<Cluster> responseObserver) {
        final String parent = request.getParent();
        final String group = removePrefix("groups/", parent);
        checkGroup(xdsCentralDogmaProject, group);

        final String clusterId = request.getClusterId();
        if (!RESOURCE_ID_PATTERN.matcher(clusterId).matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid cluster_id: " + clusterId +
                                                          " (expected: " + RESOURCE_ID_PATTERN + ')')
                                         .asRuntimeException();
        }

        final String clusterName = parent + CLUSTERS_DIRECTORY + clusterId;
        // Ignore the specified name in the cluster and set the name
        // with the format of "groups/{group}/clusters/{cluster}".
        // https://github.com/aip-dev/google.aip.dev/blob/master/aip/general/0133.md#user-specified-ids
        final Cluster cluster = request.getCluster().toBuilder().setName(clusterName).build();
        push(commandExecutor, responseObserver, group, CLUSTERS_DIRECTORY + clusterId + ".json",
             "Create cluster: " + clusterName, cluster, currentAuthor());
    }

    @Override
    public void updateCluster(UpdateClusterRequest request, StreamObserver<Cluster> responseObserver) {
        final Cluster cluster = request.getCluster();
        final String clusterName = cluster.getName();
        final Matcher matcher = checkClusterName(clusterName);
        update(commandExecutor, xdsCentralDogmaProject, matcher.group(1),
               responseObserver, clusterName, "Update cluster: " + clusterName, cluster);
    }

    @Override
    public void deleteCluster(DeleteClusterRequest request, StreamObserver<Empty> responseObserver) {
        final String clusterName = request.getName();
        final Matcher matcher = checkClusterName(clusterName);
        delete(commandExecutor, xdsCentralDogmaProject, matcher.group(1), responseObserver,
               clusterName, "Delete cluster: " + clusterName);
    }

    private static Matcher checkClusterName(String clusterName) {
        final Matcher matcher = CLUSTER_NAME_PATTERN.matcher(clusterName);
        if (!matcher.matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid cluster name: " + clusterName +
                                                          " (expected: " + CLUSTER_NAME_PATTERN + ')')
                                         .asRuntimeException();
        }
        return matcher;
    }
}
