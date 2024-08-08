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
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN_STRING;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.removePrefix;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.protobuf.Empty;

import com.linecorp.centraldogma.xds.internal.XdsResourceManager;
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

    private final XdsResourceManager xdsResourceManager;

    /**
     * Creates a new instance.
     */
    public XdsRouteService(XdsResourceManager xdsResourceManager) {
        this.xdsResourceManager = xdsResourceManager;
    }

    @Override
    public void createRoute(CreateRouteRequest request, StreamObserver<RouteConfiguration> responseObserver) {
        final String parent = request.getParent();
        final String group = removePrefix("groups/", parent);
        xdsResourceManager.checkGroup(group);

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
        xdsResourceManager.push(responseObserver, group, ROUTES_DIRECTORY + routeId + ".json",
                                "Create route: " + routeName, route, currentAuthor());
    }

    @Override
    public void updateRoute(UpdateRouteRequest request, StreamObserver<RouteConfiguration> responseObserver) {
        final RouteConfiguration route = request.getRoute();
        final String routeName = route.getName();
        final String group = checkRouteName(routeName).group(1);
        xdsResourceManager.update(responseObserver, group, routeName, "Update route: " + routeName, route);
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
        final String group = checkRouteName(routeName).group(1);
        xdsResourceManager.delete(responseObserver, group, routeName, "Delete route: " + routeName);
    }
}
