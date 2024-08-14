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
package com.linecorp.centraldogma.xds.internal;

import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ONE_WITHOUT_CONTENT;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.regex.Pattern;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Empty;
import com.google.protobuf.Message;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.xds.k8s.v1.CreateServiceEndpointWatcherRequest;
import com.linecorp.centraldogma.xds.k8s.v1.DeleteServiceEndpointWatcherRequest;
import com.linecorp.centraldogma.xds.k8s.v1.UpdateServiceEndpointWatcherRequest;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public final class XdsResourceManager {

    public static final String RESOURCE_ID_PATTERN_STRING = "[a-z](?:[a-z0-9-_/]*[a-z0-9])?";
    public static final Pattern RESOURCE_ID_PATTERN = Pattern.compile('^' + RESOURCE_ID_PATTERN_STRING + '$');

    //TODO(minwoox): Automate the registration of the extension message types.
    public static final MessageMarshaller JSON_MESSAGE_MARSHALLER =
            MessageMarshaller.builder().omittingInsignificantWhitespace(true)
                             .register(Listener.getDefaultInstance())
                             .register(Cluster.getDefaultInstance())
                             .register(ClusterLoadAssignment.getDefaultInstance())
                             .register(RouteConfiguration.getDefaultInstance())
                             .register(CreateServiceEndpointWatcherRequest.getDefaultInstance())
                             .register(UpdateServiceEndpointWatcherRequest.getDefaultInstance())
                             .register(DeleteServiceEndpointWatcherRequest.getDefaultInstance())
                             // extensions
                             .register(Router.getDefaultInstance())
                             .register(HttpConnectionManager.getDefaultInstance())
                             .register(UpstreamTlsContext.getDefaultInstance())
                             .register(DownstreamTlsContext.getDefaultInstance())
                             .build();

    public static String removePrefix(String prefix, String name) {
        if (!name.startsWith(prefix)) {
            throw Status.INVALID_ARGUMENT.withDescription(name + " does not start with prefix: " + prefix)
                                         .asRuntimeException();
        }
        return name.substring(prefix.length());
    }

    private final Project xdsProject;
    private final CommandExecutor commandExecutor;

    /**
     * Creates a new instance.
     */
    public XdsResourceManager(Project xdsProject, CommandExecutor commandExecutor) {
        this.xdsProject = requireNonNull(xdsProject, "xdsProject");
        this.commandExecutor = requireNonNull(commandExecutor, "commandExecutor");
    }

    public void checkGroup(String group) {
        // TODO(minwoox): check the write permission.
        if (!xdsProject.repos().exists(group)) {
            throw Status.NOT_FOUND.withDescription("Group not found: " + group).asRuntimeException();
        }
    }

    public <T extends Message> void push(
            StreamObserver<T> responseObserver, String group, String fileName,
            String summary, T resource, Author author) {
        final Change<JsonNode> change;
        try {
            change = Change.ofJsonUpsert(fileName, JSON_MESSAGE_MARSHALLER.writeValueAsString(resource));
        } catch (IOException e) {
            // This could happen when the message has a type that isn't registered to JSON_MESSAGE_MARSHALLER.
            responseObserver.onError(Status.INTERNAL.withCause(new IllegalStateException(
                    "failed to convert message to JSON: " + resource, e)).asRuntimeException());
            return;
        }
        commandExecutor.execute(Command.push(author, XDS_CENTRAL_DOGMA_PROJECT, group, Revision.HEAD,
                                             summary, "", Markup.PLAINTEXT, ImmutableList.of(change)))
                       .handle((unused, cause) -> {
                           if (cause != null) {
                               responseObserver.onError(Status.INTERNAL.withCause(cause).asRuntimeException());
                               return null;
                           }
                           responseObserver.onNext(resource);
                           responseObserver.onCompleted();
                           return null;
                       });
    }

    private static String fileName(String group, String resourceName) {
        // Remove groups/{group}
        return resourceName.substring(7 + group.length()) + ".json";
    }

    public <T extends Message> void update(StreamObserver<T> responseObserver, String group,
                                           String resourceName, String summary, T resource, Author author) {
        update(responseObserver, group, resourceName, fileName(group, resourceName), summary, resource, author);
    }

    public <T extends Message> void update(StreamObserver<T> responseObserver, String group,
                                           String resourceName, String fileName, String summary, T resource,
                                           Author author) {
        updateOrDelete(responseObserver, group, resourceName, fileName,
                       () -> push(responseObserver, group, fileName, summary, resource, author));
    }

    public void delete(StreamObserver<Empty> responseObserver, String group,
                       String resourceName, String summary, Author author) {
        delete(responseObserver, group, resourceName, fileName(group, resourceName), summary, author);
    }

    public void delete(StreamObserver<Empty> responseObserver, String group,
                       String resourceName, String fileName, String summary, Author author) {
        final Runnable deleteTask = () ->
                commandExecutor.execute(Command.push(author, XDS_CENTRAL_DOGMA_PROJECT, group,
                                                     Revision.HEAD, summary, "", Markup.PLAINTEXT,
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
        updateOrDelete(responseObserver, group, resourceName, fileName, deleteTask);
    }

    public void updateOrDelete(StreamObserver<?> responseObserver, String group, String resourceName,
                               String fileName, Runnable task) {
        final Repository repository = xdsProject.repos().get(group);
        repository.find(Revision.HEAD, fileName, FIND_ONE_WITHOUT_CONTENT).handle((entries, cause) -> {
            if (cause != null) {
                responseObserver.onError(Status.INTERNAL.withCause(cause).asRuntimeException());
                return null;
            }
            if (entries.isEmpty()) {
                // TODO(minwoox): implement allowMissing.
                responseObserver.onError(Status.NOT_FOUND.withDescription("Resource not found: " + resourceName)
                                                         .asRuntimeException());
                return null;
            }
            task.run();
            return null;
        });
    }
}
