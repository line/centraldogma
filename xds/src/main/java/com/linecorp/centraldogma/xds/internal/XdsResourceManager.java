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

import static com.linecorp.centraldogma.server.internal.storage.InternalProjectConstants.INTERNAL_PROJECT_XDS;
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ONE_WITHOUT_CONTENT;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.jspecify.annotations.Nullable;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Yaml;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.xds.endpoint.v1.LocalityLbEndpoint;
import com.linecorp.centraldogma.xds.k8s.v1.KubernetesEndpointAggregator;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

public final class XdsResourceManager {

    public static final String RESOURCE_ID_PATTERN_STRING = "[a-z](?:[a-z0-9_.-]*[a-z0-9])?";
    public static final Pattern RESOURCE_ID_PATTERN = Pattern.compile('^' + RESOURCE_ID_PATTERN_STRING + '$');
    // Allows slashes in addition to dots for backward compatibility with resources created before the
    // slash was forbidden. Use this pattern only for parsing existing resource names in update/delete
    // operations, not for validating new IDs in create operations.
    public static final String LEGACY_RESOURCE_ID_PATTERN_STRING = "[a-z](?:[a-z0-9_/.-]*[a-z0-9])?";
    public static final Pattern LEGACY_RESOURCE_ID_PATTERN =
            Pattern.compile('^' + LEGACY_RESOURCE_ID_PATTERN_STRING + '$');

    public static final MediaType MEDIA_TYPE_YAML = MediaType.parse("application/yaml");

    public static final MessageMarshaller JSON_MESSAGE_MARSHALLER;

    static {
        final MessageMarshaller.Builder builder =
                MessageMarshaller.builder().omittingInsignificantWhitespace(true);
        builder.register(Listener.getDefaultInstance())
               .register(Cluster.getDefaultInstance())
               .register(ClusterLoadAssignment.getDefaultInstance())
               .register(RouteConfiguration.getDefaultInstance())
               .register(KubernetesEndpointAggregator.getDefaultInstance())
               .register(LocalityLbEndpoint.getDefaultInstance());
        envoyExtension(builder);
        JSON_MESSAGE_MARSHALLER = builder.build();
    }

    private static void envoyExtension(MessageMarshaller.Builder builder) {
        final Reflections reflections = new Reflections(
                "io.envoyproxy.envoy.extensions", HttpConnectionManager.class.getClassLoader(),
                new SubTypesScanner(true));
        reflections.getSubTypesOf(GeneratedMessageV3.class)
                   .stream()
                   .filter(c -> !c.getName().contains("$")) // exclude subclasses
                   .filter(XdsResourceManager::hasGetDefaultInstanceMethod)
                   .forEach(builder::register);
    }

    private static boolean hasGetDefaultInstanceMethod(Class<?> clazz) {
        try {
            final Method method = clazz.getMethod("getDefaultInstance");
            return method.getParameterCount() == 0 && Modifier.isStatic(method.getModifiers());
        } catch (NoSuchMethodException ignored) {
            return false;
        }
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

    public Project xdsProject() {
        return xdsProject;
    }

    public CommandExecutor commandExecutor() {
        return commandExecutor;
    }

    public static String fileName(String group, String resourceName) {
        // Remove groups/{group}
        return resourceName.substring(7 + group.length()) + ".yaml";
    }

    public static HttpResponse errorResponse(HttpStatus status, String message) {
        return HttpResponse.of(status, MediaType.PLAIN_TEXT_UTF_8, message);
    }

    public static HttpResponse errorResponse(HttpStatus status, Throwable cause) {
        final String message = cause.getMessage();
        return errorResponse(status, message != null ? message : cause.toString());
    }

    public static HttpResponse toYamlResponse(Message resource) throws IOException {
        return HttpResponse.of(MEDIA_TYPE_YAML, toYamlBodyString(resource));
    }

    public static HttpResponse toYamlResponse(String yaml) {
        return HttpResponse.of(MEDIA_TYPE_YAML, yaml);
    }

    public static String toYamlBodyString(Message resource) throws IOException {
        final String json = JSON_MESSAGE_MARSHALLER.writeValueAsString(resource);
        final JsonNode node = Jackson.readTree(json);
        return Yaml.writeValueAsString(node);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Message> T parseYaml(String body, Message.Builder builder) throws IOException {
        JSON_MESSAGE_MARSHALLER.mergeValue(Yaml.readTree(body).traverse(), builder);
        return (T) builder.build();
    }

    // Matches a snake_case identifier: at least one underscore, starts with lowercase.
    // Used to detect mapping keys that need camelCase conversion.
    private static final Pattern SNAKE_CASE_IDENTIFIER =
            Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)+");

    /**
     * Converts all snake_case mapping keys in the given YAML string to camelCase, preserving
     * values, comments, block scalars, and formatting. Uses snakeyaml's AST to locate real
     * mapping keys, so block-scalar content lines are never mistaken for keys.
     *
     * <p>Call this on user-supplied bodies before storing them so that all stored YAML uses
     * camelCase keys consistently, matching what the proto3 JSON serializer produces.
     */
    public static String normalizeYamlKeys(String yaml) {
        if (!yaml.contains("_")) {
            return yaml;
        }
        final Node root = composeYaml(yaml);
        if (root == null) {
            return yaml;
        }
        final List<int[]> ranges = new ArrayList<>();
        collectSnakeCaseKeyRanges(root, ranges);
        if (ranges.isEmpty()) {
            return yaml;
        }
        // Replace from end to start so earlier offsets stay valid.
        ranges.sort((a, b) -> Integer.compare(b[0], a[0]));
        final StringBuilder sb = new StringBuilder(yaml);
        for (int[] range : ranges) {
            sb.replace(range[0], range[1], snakeToCamel(yaml.substring(range[0], range[1])));
        }
        return sb.toString();
    }

    private static void collectSnakeCaseKeyRanges(Node node, List<int[]> ranges) {
        if (node instanceof MappingNode) {
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                final Node keyNode = tuple.getKeyNode();
                if (keyNode instanceof ScalarNode) {
                    final String key = ((ScalarNode) keyNode).getValue();
                    if (SNAKE_CASE_IDENTIFIER.matcher(key).matches()) {
                        final int start = keyNode.getStartMark().getIndex();
                        // Use key.length() rather than endMark to avoid trailing-whitespace
                        // ambiguity (e.g. "key : value" where the space before ':' is trimmed
                        // from the value but may or may not be included in endMark).
                        ranges.add(new int[] { start, start + key.length() });
                    }
                }
                // Recurse into the value — never into ScalarNode leaves (block scalar content).
                collectSnakeCaseKeyRanges(tuple.getValueNode(), ranges);
            }
        } else if (node instanceof SequenceNode) {
            for (Node item : ((SequenceNode) node).getValue()) {
                collectSnakeCaseKeyRanges(item, ranges);
            }
        }
    }

    private static String snakeToCamel(String snake) {
        final StringBuilder sb = new StringBuilder(snake.length());
        boolean upper = false;
        for (int i = 0; i < snake.length(); i++) {
            final char c = snake.charAt(i);
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    /**
     * Injects or replaces a top-level YAML field in the given YAML string, preserving all other
     * content including comments. If {@code fieldName} is not found as a top-level key, it is
     * prepended. Uses snakeyaml's AST so that block-scalar values (e.g. {@code name: |}) are
     * replaced in their entirety — not just the indicator line.
     *
     * <p>Call {@link #normalizeYamlKeys(String)} before this method so that the key name is
     * already in camelCase.
     */
    public static String injectYamlField(String yaml, String fieldName, String fieldValue) {
        final Node root = composeYaml(yaml);
        if (root instanceof MappingNode) {
            for (NodeTuple tuple : ((MappingNode) root).getValue()) {
                final Node keyNode = tuple.getKeyNode();
                if (keyNode instanceof ScalarNode &&
                    fieldName.equals(((ScalarNode) keyNode).getValue())) {
                    final int keyStart = keyNode.getStartMark().getIndex();
                    // End of the value node; skip one trailing newline so we don't leave a blank
                    // line when the value's endMark lands on the '\n' that separates entries.
                    int valueEnd = tuple.getValueNode().getEndMark().getIndex();
                    if (valueEnd < yaml.length() && yaml.charAt(valueEnd) == '\n') {
                        valueEnd++;
                    }
                    return yaml.substring(0, keyStart) +
                           fieldName + ": " + fieldValue + '\n' +
                           yaml.substring(valueEnd);
                }
            }
        }
        return fieldName + ": " + fieldValue + '\n' + yaml;
    }

    @Nullable
    private static Node composeYaml(String yaml) {
        try {
            return new org.yaml.snakeyaml.Yaml().compose(new StringReader(yaml));
        } catch (Exception ignored) {
            return null;
        }
    }

    public CompletableFuture<HttpResponse> push(
            String group, String resourceName, String fileName, String summary,
            Author author, boolean create, String originalBody) {
        if (create) {
            final Repository repository;
            try {
                repository = xdsProject.repos().get(group);
            } catch (Exception e) {
                return CompletableFuture.completedFuture(
                        errorResponse(HttpStatus.NOT_FOUND, "Group not found: " + group));
            }
            final String altFileName = alternativeFileName(fileName);
            // Note: There is a TOCTOU race between this check and the subsequent ofYamlUpsert —
            // two concurrent creates can both pass this check and the second will silently overwrite
            // the first. This is acceptable for xDS resources where concurrent creates of the same
            // resource are extremely rare.
            return repository.find(Revision.HEAD, fileName + ',' + altFileName, FIND_ONE_WITHOUT_CONTENT)
                             .thenCompose(entries -> {
                                 if (!entries.isEmpty()) {
                                     return CompletableFuture.completedFuture(
                                             errorResponse(HttpStatus.CONFLICT,
                                                           "Resource already exists: " + resourceName));
                                 }
                                 return doPush(group, fileName, summary, author,
                                               true, null, originalBody);
                             });
        }
        return doPush(group, fileName, summary, author, false, null, originalBody);
    }

    private CompletableFuture<HttpResponse> doPush(
            String group, String fileName, String summary,
            Author author, boolean create, @Nullable String legacyFileToRemove, String originalBody) {
        // Store the original YAML body as-is (server-set fields are injected by the caller before
        // this method is invoked). Respond with the same body so the client sees exactly what is stored.
        final Change<?> change = Change.ofYamlUpsert(fileName, originalBody);
        final ImmutableList<Change<?>> changes =
                legacyFileToRemove != null ? ImmutableList.of(Change.ofRemoval(legacyFileToRemove), change)
                                           : ImmutableList.of(change);
        return commandExecutor.execute(Command.push(author, INTERNAL_PROJECT_XDS, group, Revision.HEAD,
                                                    summary, "", Markup.PLAINTEXT, changes))
                              .handle((unused, cause) -> {
                                  if (cause != null) {
                                      final Throwable peeled = Exceptions.peel(cause);
                                      if (!create && peeled instanceof RedundantChangeException) {
                                          return toYamlResponse(originalBody);
                                      }
                                      return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, peeled);
                                  }
                                  return toYamlResponse(originalBody);
                              });
    }

    public CompletableFuture<HttpResponse> update(
            String group, String resourceName, String summary, Author author, String originalBody) {
        return update(group, resourceName, fileName(group, resourceName), summary, author, originalBody);
    }

    public CompletableFuture<HttpResponse> update(
            String group, String resourceName, String fileName, String summary,
            Author author, String originalBody) {
        return updateOrDelete(group, resourceName, fileName, resolvedFileName -> {
            final String legacyFileToRemove = resolvedFileName.endsWith(".json") ? resolvedFileName : null;
            final String targetFileName = legacyFileToRemove != null ? fileName : resolvedFileName;
            return doPush(group, targetFileName, summary, author, false, legacyFileToRemove, originalBody);
        });
    }

    public CompletableFuture<HttpResponse> delete(
            String group, String resourceName, String summary, Author author) {
        return delete(group, resourceName, fileName(group, resourceName), summary, author);
    }

    public CompletableFuture<HttpResponse> delete(
            String group, String resourceName, String fileName, String summary, Author author) {
        return updateOrDelete(group, resourceName, fileName, resolvedFileName ->
                commandExecutor.execute(Command.push(author, INTERNAL_PROJECT_XDS, group,
                                                     Revision.HEAD, summary, "", Markup.PLAINTEXT,
                                                     ImmutableList.of(Change.ofRemoval(resolvedFileName))))
                               .handle((unused, cause) -> {
                                   if (cause != null) {
                                       return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                                                            Exceptions.peel(cause));
                                   }
                                   return HttpResponse.of(HttpStatus.OK);
                               }));
    }

    public CompletableFuture<HttpResponse> updateOrDelete(
            String group, String resourceName, String fileName,
            Function<String, CompletableFuture<HttpResponse>> taskProvider) {
        final Repository repository;
        try {
            repository = xdsProject.repos().get(group);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    errorResponse(HttpStatus.NOT_FOUND, "Group not found: " + group));
        }
        final String altFileName = alternativeFileName(fileName);
        return repository.find(Revision.HEAD, fileName + ',' + altFileName, FIND_ONE_WITHOUT_CONTENT)
                         .thenCompose(entries -> {
                             if (entries.isEmpty()) {
                                 return CompletableFuture.completedFuture(
                                         errorResponse(HttpStatus.NOT_FOUND,
                                                       "Resource not found: " + resourceName));
                             }
                             final String resolvedFileName = entries.keySet().iterator().next();
                             return taskProvider.apply(resolvedFileName);
                         });
    }

    public static String alternativeFileName(String fileName) {
        if (fileName.endsWith(".json")) {
            return fileName.substring(0, fileName.length() - 5) + ".yaml";
        }
        if (fileName.endsWith(".yaml")) {
            return fileName.substring(0, fileName.length() - 5) + ".json";
        }
        return fileName;
    }
}
