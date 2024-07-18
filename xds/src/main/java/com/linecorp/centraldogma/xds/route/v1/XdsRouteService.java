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
package com.linecorp.centraldogma.xds.route.v1;

import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.ROUTES_DIRECTORY;
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
import com.linecorp.centraldogma.xds.route.v1.XdsRouteServiceGrpc.XdsRouteServiceImplBase;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Service for managing routes.
 */
public final class XdsRouteService extends XdsRouteServiceImplBase {

    private static final Pattern ROUTE_NAME_PATTERN =
            Pattern.compile("^groups/([^/]+)/routes/" + RESOURCE_ID_PATTERN_STRING + '$');

    private final Project xdsCentralDogmaProject;
    private final CommandExecutor commandExecutor;

    /**
     * Creates a new instance.
     */
    public XdsRouteService(ProjectManager projectManager, CommandExecutor commandExecutor) {
        xdsCentralDogmaProject = requireNonNull(projectManager, "projectManager")
                .get(XDS_CENTRAL_DOGMA_PROJECT);
        this.commandExecutor = requireNonNull(commandExecutor, "commandExecutor");
    }

    @Override
    public void createRoute(CreateRouteRequest request, StreamObserver<RouteConfiguration> responseObserver) {
        final String parent = request.getParent();
        final String group = removePrefix("groups/", parent);
        checkGroup(xdsCentralDogmaProject, group);

        final String routeId = request.getRouteId();
        if (!RESOURCE_ID_PATTERN.matcher(routeId).matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid route_id: " + routeId +
                                                          " (expected: " + RESOURCE_ID_PATTERN + ')')
                                         .asRuntimeException();
        }

        final String routeName = parent + ROUTES_DIRECTORY + routeId;
        // Ignore the specified name in the route and set the name
        // with the format of "groups/{group}/routes/{route}".
        // https://github.com/aip-dev/google.aip.dev/blob/master/aip/general/0133.md#user-specified-ids
        final RouteConfiguration route = request.getRoute().toBuilder().setName(routeName).build();
        push(commandExecutor, responseObserver, group, ROUTES_DIRECTORY + routeId + ".json",
             "Create route: " + routeName, route, currentAuthor());
    }

    @Override
    public void updateRoute(UpdateRouteRequest request, StreamObserver<RouteConfiguration> responseObserver) {
        final RouteConfiguration route = request.getRoute();
        final String routeName = route.getName();
        final Matcher matcher = checkRouteName(routeName);
        update(commandExecutor, xdsCentralDogmaProject, matcher.group(1), responseObserver, routeName,
               "Update route: " + routeName, route);
    }

    private static Matcher checkRouteName(String routeName) {
        final Matcher matcher = ROUTE_NAME_PATTERN.matcher(routeName);
        if (!matcher.matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid route name: " + routeName +
                                                          " (expected: " + ROUTE_NAME_PATTERN + ')')
                                         .asRuntimeException();
        }
        return matcher;
    }

    @Override
    public void deleteRoute(DeleteRouteRequest request, StreamObserver<Empty> responseObserver) {
        final String routeName = request.getName();
        final Matcher matcher = checkRouteName(routeName);
        delete(commandExecutor, xdsCentralDogmaProject, matcher.group(1), responseObserver,
               routeName, "Delete route: " + routeName);
    }
}
