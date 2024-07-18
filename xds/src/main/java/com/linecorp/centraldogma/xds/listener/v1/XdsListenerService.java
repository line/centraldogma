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
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ONE_WITHOUT_CONTENT;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.LISTENERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Empty;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.xds.listener.v1.XdsListenerServiceGrpc.XdsListenerServiceImplBase;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public final class XdsListenerService extends XdsListenerServiceImplBase {

    private static final String RESOURCE_ID_PATTERN_STRING = "[a-z](?:[a-z0-9-_/]*[a-z0-9])?";
    private static final Pattern RESOURCE_ID_PATTERN = Pattern.compile('^' + RESOURCE_ID_PATTERN_STRING + '$');
    private static final Pattern LISTENER_NAME_PATTERN =
            Pattern.compile("^groups/([^/]+)/listeners/" + RESOURCE_ID_PATTERN_STRING + '$');

    public static final MessageMarshaller JSON_MESSAGE_MARSHALLER =
            MessageMarshaller.builder().omittingInsignificantWhitespace(true).register(
                                     Listener.getDefaultInstance()).register(HttpConnectionManager.getDefaultInstance())
                             .register(Router.getDefaultInstance()).build();

    private final Project xdsCentralDogmaProject;
    private final CommandExecutor commandExecutor;

    /**
     * Creates a new instance.
     */
    public XdsListenerService(ProjectManager projectManager, CommandExecutor commandExecutor) {
        xdsCentralDogmaProject = requireNonNull(projectManager, "projectManager").get(
                XDS_CENTRAL_DOGMA_PROJECT);
        this.commandExecutor = requireNonNull(commandExecutor, "commandExecutor");
    }

    @Override
    public void createListener(CreateListenerRequest request, StreamObserver<Listener> responseObserver) {
        final String parent = request.getParent();
        final String group = removePrefix("groups/", parent);
        checkGroup(group);

        final String listenerId = request.getListenerId();
        if (!RESOURCE_ID_PATTERN.matcher(listenerId).matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid listener_id: " + listenerId +
                                                          "(expected: " + RESOURCE_ID_PATTERN + ')')
                                         .asRuntimeException();
        }

        final String listenerName = parent + LISTENERS_DIRECTORY + listenerId;
        // Ignore the specified name in the listener and set the name
        // with the format of "groups/{group}/listeners/{listener}".
        // https://github.com/aip-dev/google.aip.dev/blob/master/aip/general/0133.md#user-specified-ids
        final Listener listener = request.getListener().toBuilder().setName(listenerName).build();
        push(responseObserver, group, LISTENERS_DIRECTORY + listenerId + ".json",
             listenerName, "Create", listener);
    }

    private void push(StreamObserver<Listener> responseObserver, String group, String fileName,
                      String listenerName, String createOrUpdate, Listener listener) {
        final Change<JsonNode> change;
        try {
            change = Change.ofJsonUpsert(fileName, JSON_MESSAGE_MARSHALLER.writeValueAsString(listener));
        } catch (IOException e) {
            // This could happen when the message has a type that isn't registered to JSON_MESSAGE_MARSHALLER.
            responseObserver.onError(Status.INTERNAL.withCause(new IllegalStateException(
                    "failed to convert message to JSON: " + listener, e)).asRuntimeException());
            return;
        }
        commandExecutor.execute(Command.push(currentAuthor(), XDS_CENTRAL_DOGMA_PROJECT, group, Revision.HEAD,
                                             createOrUpdate + " listener: " + listenerName, "",
                                             Markup.PLAINTEXT, ImmutableList.of(change)))
                       .handle((unused, cause) -> {
                           if (cause != null) {
                               responseObserver.onError(Status.INTERNAL.withCause(cause).asRuntimeException());
                               return null;
                           }
                           responseObserver.onNext(listener);
                           responseObserver.onCompleted();
                           return null;
                       });
    }

    private static String removePrefix(String prefix, String name) {
        if (!name.startsWith(prefix)) {
            throw Status.INVALID_ARGUMENT.withDescription(name + " does not start with prefix: " + prefix)
                                         .asRuntimeException();
        }
        return name.substring(prefix.length());
    }

    private void checkGroup(String group) {
        // TODO(minwoox): check the write permission.
        if (!xdsCentralDogmaProject.repos().exists(group)) {
            throw Status.NOT_FOUND.withDescription("Group not found: " + group).asRuntimeException();
        }
    }

    @Override
    public void updateListener(UpdateListenerRequest request, StreamObserver<Listener> responseObserver) {
        final Listener listener = request.getListener();
        final String listenerName = listener.getName();
        final BiConsumer<String, String> updateTask = (group, fileName) -> push(responseObserver, group, fileName, listenerName, "Update", listener);
        updateOrDelete(responseObserver, listenerName, updateTask);
    }

    private void updateOrDelete(StreamObserver<?> responseObserver, String listenerName,
                                BiConsumer<String, String> task) {
        final Matcher matcher = checkListenerName(listenerName);
        final String group = matcher.group(1);
        checkGroup(group);
        final Repository repository = xdsCentralDogmaProject.repos().get(group);
        final String fileName = listenerName.substring(("groups/" + group).length()) + ".json";
        repository.find(Revision.HEAD, fileName, FIND_ONE_WITHOUT_CONTENT).handle((entries, cause) -> {
            if (cause != null) {
                responseObserver.onError(Status.INTERNAL.withCause(cause).asRuntimeException());
                return null;
            }
            if (entries.isEmpty()) {
                // TODO(minwoox): implement allowMissing.
                responseObserver.onError(Status.NOT_FOUND.withDescription("Listener not found: " + listenerName)
                                                         .asRuntimeException());
                return null;
            }
            task.accept(group, fileName);
            return null;
        });
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
        final BiConsumer<String, String> deleteTask = (group, fileName) -> {
            commandExecutor.execute(Command.push(currentAuthor(), XDS_CENTRAL_DOGMA_PROJECT, group,
                                                 Revision.HEAD, "Delete listener: " + listenerName, "",
                                                 Markup.PLAINTEXT,
                                                 ImmutableList.of(Change.ofRemoval(fileName))))
                           .handle((unused, cause) -> {
                               if (cause != null) {
                                   responseObserver.onError(
                                           Status.INTERNAL.withCause(cause).asRuntimeException());
                                   return null;
                               }
                               responseObserver.onNext(Empty.getDefaultInstance());
                               responseObserver.onCompleted();
                               return null;
                           });
        };
        updateOrDelete(responseObserver, listenerName, deleteTask);
    }
}
