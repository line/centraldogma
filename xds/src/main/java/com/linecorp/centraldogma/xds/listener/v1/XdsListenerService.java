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
package com.linecorp.centraldogma.xds.listener.v1;

import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.LISTENERS_DIRECTORY;
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
import com.linecorp.centraldogma.xds.listener.v1.XdsListenerServiceGrpc.XdsListenerServiceImplBase;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Service for managing listeners.
 */
public final class XdsListenerService extends XdsListenerServiceImplBase {

    private static final Pattern LISTENER_NAME_PATTERN =
            Pattern.compile("^groups/([^/]+)/listeners/" + RESOURCE_ID_PATTERN_STRING + '$');

    private final Project xdsCentralDogmaProject;
    private final CommandExecutor commandExecutor;

    /**
     * Creates a new instance.
     */
    public XdsListenerService(ProjectManager projectManager, CommandExecutor commandExecutor) {
        xdsCentralDogmaProject = requireNonNull(projectManager, "projectManager")
                .get(XDS_CENTRAL_DOGMA_PROJECT);
        this.commandExecutor = requireNonNull(commandExecutor, "commandExecutor");
    }

    @Override
    public void createListener(CreateListenerRequest request, StreamObserver<Listener> responseObserver) {
        final String parent = request.getParent();
        final String group = removePrefix("groups/", parent);
        checkGroup(xdsCentralDogmaProject, group);

        final String listenerId = request.getListenerId();
        if (!RESOURCE_ID_PATTERN.matcher(listenerId).matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid listener_id: " + listenerId +
                                                          " (expected: " + RESOURCE_ID_PATTERN + ')')
                                         .asRuntimeException();
        }

        final String listenerName = parent + LISTENERS_DIRECTORY + listenerId;
        // Ignore the specified name in the listener and set the name
        // with the format of "groups/{group}/listeners/{listener}".
        // https://github.com/aip-dev/google.aip.dev/blob/master/aip/general/0133.md#user-specified-ids
        final Listener listener = request.getListener().toBuilder().setName(listenerName).build();
        push(commandExecutor, responseObserver, group, LISTENERS_DIRECTORY + listenerId + ".json",
             "Create listener: " + listenerName, listener, currentAuthor());
    }

    @Override
    public void updateListener(UpdateListenerRequest request, StreamObserver<Listener> responseObserver) {
        final Listener listener = request.getListener();
        final String listenerName = listener.getName();
        final Matcher matcher = checkListenerName(listenerName);
        update(commandExecutor, xdsCentralDogmaProject, matcher.group(1),
               responseObserver, listenerName, "Update listener: " + listenerName, listener);
    }

    private static Matcher checkListenerName(String listenerName) {
        final Matcher matcher = LISTENER_NAME_PATTERN.matcher(listenerName);
        if (!matcher.matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid listener name: " + listenerName +
                                                          " (expected: " + LISTENER_NAME_PATTERN + ')')
                                         .asRuntimeException();
        }
        return matcher;
    }

    @Override
    public void deleteListener(DeleteListenerRequest request, StreamObserver<Empty> responseObserver) {
        final String listenerName = request.getName();
        final Matcher matcher = checkListenerName(listenerName);
        delete(commandExecutor, xdsCentralDogmaProject, matcher.group(1), responseObserver,
               listenerName, "Delete listener: " + listenerName);
    }
}