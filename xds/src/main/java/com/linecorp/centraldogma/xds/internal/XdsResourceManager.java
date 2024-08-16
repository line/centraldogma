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

import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ONE_WITHOUT_CONTENT;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Empty;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.Message;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public final class XdsResourceManager {

    public static final String RESOURCE_ID_PATTERN_STRING = "[a-z](?:[a-z0-9-_/]*[a-z0-9])?";
    public static final Pattern RESOURCE_ID_PATTERN = Pattern.compile('^' + RESOURCE_ID_PATTERN_STRING + '$');

    public static final MessageMarshaller JSON_MESSAGE_MARSHALLER =
            registerEnvoyExtension(
                    MessageMarshaller.builder().omittingInsignificantWhitespace(true)
                                     .register(Listener.getDefaultInstance())
                                     .register(Cluster.getDefaultInstance())
                                     .register(ClusterLoadAssignment.getDefaultInstance())
                                     .register(RouteConfiguration.getDefaultInstance()))
                    .build();

    public static MessageMarshaller.Builder registerEnvoyExtension(MessageMarshaller.Builder builder) {
        final Reflections reflections = new Reflections(
                "io.envoyproxy.envoy.extensions", HttpConnectionManager.class.getClassLoader(),
                new SubTypesScanner(true));
        reflections.getSubTypesOf(GeneratedMessageV3.class)
                   .stream()
                   .filter(c -> !c.getName().contains("$")) // exclude subclasses
                   .filter(XdsResourceManager::hasGetDefaultInstanceMethod)
                   .forEach(builder::register);
        return builder;
    }

    private static boolean hasGetDefaultInstanceMethod(Class<?> clazz) {
        try {
            final Method method = clazz.getMethod("getDefaultInstance");
            return method.getParameterCount() == 0 && Modifier.isStatic(method.getModifiers());
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    public static String removePrefix(String prefix, String name) {
        if (!name.startsWith(prefix)) {
            throw Status.INVALID_ARGUMENT.withDescription(name + " does not start with prefix: " + prefix)
                                         .asRuntimeException();
        }
        return name.substring(prefix.length());
    }

    private final Project xdsCentralDogmaProject;
    private final CommandExecutor commandExecutor;

    /**
     * Creates a new instance.
     */
    public XdsResourceManager(ProjectManager projectManager, CommandExecutor commandExecutor) {
        xdsCentralDogmaProject = requireNonNull(projectManager, "projectManager")
                .get(XDS_CENTRAL_DOGMA_PROJECT);
        this.commandExecutor = requireNonNull(commandExecutor, "commandExecutor");
    }

    public void checkGroup(String group) {
        // TODO(minwoox): check the write permission.
        if (!xdsCentralDogmaProject.repos().exists(group)) {
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

    public <T extends Message> void update(
            StreamObserver<T> responseObserver, String group, String resourceName, String summary, T resource) {
        update(responseObserver, group, resourceName, fileName(group, resourceName), summary, resource);
    }

    public <T extends Message> void update(StreamObserver<T> responseObserver, String group,
                                           String resourceName, String fileName, String summary, T resource) {
        checkGroup(group);
        final Author author = currentAuthor();
        final Runnable updateTask = () -> push(responseObserver, group, fileName, summary, resource, author);
        updateOrDelete(responseObserver, group, resourceName, fileName, updateTask);
    }

    public void delete(StreamObserver<Empty> responseObserver, String group,
                       String resourceName, String summary) {
        delete(responseObserver, group, resourceName, fileName(group, resourceName), summary);
    }

    public void delete(StreamObserver<Empty> responseObserver, String group,
                       String resourceName, String fileName, String summary) {
        checkGroup(group);
        final Author author = currentAuthor();
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
        final Repository repository = xdsCentralDogmaProject.repos().get(group);
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
