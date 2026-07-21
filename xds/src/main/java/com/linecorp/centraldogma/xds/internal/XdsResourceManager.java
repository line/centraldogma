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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.jspecify.annotations.Nullable;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

public final class XdsResourceManager {

    private static final Logger logger = LoggerFactory.getLogger(XdsResourceManager.class);

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
        // These Central Dogma-specific types are not in the io.envoyproxy.envoy package, so they
        // must be registered explicitly; envoyExtension() does not pick them up.
        builder.register(KubernetesEndpointAggregator.getDefaultInstance())
               .register(LocalityLbEndpoint.getDefaultInstance());
        envoyExtension(builder);
        JSON_MESSAGE_MARSHALLER = builder.build();
    }

    private static void envoyExtension(MessageMarshaller.Builder builder) {
        final Reflections reflections = new Reflections(
                "io.envoyproxy.envoy", HttpConnectionManager.class.getClassLoader(),
                new SubTypesScanner(true));
        reflections.getSubTypesOf(GeneratedMessageV3.class)
                   .stream()
                   .filter(c -> !c.getName().contains("$")) // exclude inner classes
                   .filter(XdsResourceManager::hasGetDefaultInstanceMethod)
                   .forEach(c -> {
                       // register() does not throw; build() does. A test build per class is needed
                       // to detect unsupported types before adding them to the real builder.
                       try {
                           MessageMarshaller.builder()
                                            .omittingInsignificantWhitespace(true)
                                            .register(c)
                                            .build();
                           builder.register(c);
                       } catch (Exception e) {
                           logger.debug("Skipping proto type not supported by MessageMarshaller: {}",
                                        c.getName());
                       }
                   });
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

    /**
     * Injects or replaces a top-level YAML field in the given YAML string, preserving all other
     * content including comments. If {@code fieldName} is not found as a top-level key, it is
     * prepended.
     */
    public static String injectYamlField(String yaml, String fieldName, String fieldValue) {
        final String replacement = fieldName + ": " + fieldValue + '\n';
        // Match a top-level key (no leading whitespace) in either camelCase or snake_case form,
        // plus its entire value — including any indented block-scalar continuation lines.
        // (?:\r?\n|\z) handles LF, CRLF, and a missing trailing newline at end of file.
        final Pattern pattern = Pattern.compile(
                "^(?:" + Pattern.quote(fieldName) + '|' +
                Pattern.quote(camelToSnake(fieldName)) +
                ")\\s*:.*(?:\\r?\\n|\\z)(?:[ \\t]+.*(?:\\r?\\n|\\z))*",
                Pattern.MULTILINE);
        final Matcher matcher = pattern.matcher(yaml);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
        }
        return replacement + yaml;
    }

    private static String camelToSnake(String camel) {
        return camel.replaceAll("([A-Z])", "_$1").toLowerCase();
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
            Function<String, CompletableFuture<HttpResponse>> task) {
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
                             return task.apply(resolvedFileName);
                         });
    }

    public static String alternativeFileName(String fileName) {
        final String baseFileName = fileName.substring(0, fileName.length() - 5);
        if (fileName.endsWith(".json")) {
            return baseFileName + ".yaml";
        }
        if (fileName.endsWith(".yaml")) {
            return baseFileName + ".json";
        }
        return fileName;
    }
}
