/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.centraldogma.client.armeria;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.centraldogma.internal.Util.unsafeCast;
import static com.linecorp.centraldogma.internal.Util.validatePathPattern;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static com.spotify.futures.CompletableFutures.exceptionallyCompletedFuture;
import static java.util.Objects.requireNonNull;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.math.LongMath;
import com.google.common.net.UrlEscapers;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.centraldogma.client.AbstractCentralDogma;
import com.linecorp.centraldogma.client.RepositoryInfo;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.AuthorizationException;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryExecutionException;
import com.linecorp.centraldogma.common.QueryType;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.api.v1.WatchTimeout;

final class ArmeriaCentralDogma extends AbstractCentralDogma {

    private static final MediaType JSON_PATCH_UTF8 = MediaType.JSON_PATCH.withCharset(StandardCharsets.UTF_8);

    private static final byte[] UNREMOVE_PATCH = toBytes(JsonNodeFactory.instance.arrayNode(1).add(
            JsonNodeFactory.instance.objectNode().put("op", "replace")
                                    .put("path", "/status")
                                    .put("value", "active")));
    private static final String REMOVED_PARAM = "?status=removed";

    private static final Map<String, Function<String, CentralDogmaException>> EXCEPTION_FACTORIES =
            ImmutableMap.<String, Function<String, CentralDogmaException>>builder()
                    .put(ProjectExistsException.class.getName(), ProjectExistsException::new)
                    .put(ProjectNotFoundException.class.getName(), ProjectNotFoundException::new)
                    .put(QueryExecutionException.class.getName(), QueryExecutionException::new)
                    .put(RedundantChangeException.class.getName(), RedundantChangeException::new)
                    .put(RevisionNotFoundException.class.getName(), RevisionNotFoundException::new)
                    .put(EntryNotFoundException.class.getName(), EntryNotFoundException::new)
                    .put(ChangeConflictException.class.getName(), ChangeConflictException::new)
                    .put(RepositoryNotFoundException.class.getName(), RepositoryNotFoundException::new)
                    .put(AuthorizationException.class.getName(), AuthorizationException::new)
                    .put(ShuttingDownException.class.getName(), ShuttingDownException::new)
                    .put(RepositoryExistsException.class.getName(), RepositoryExistsException::new)
                    .build();

    private final HttpClient client;
    private final String authorization;

    ArmeriaCentralDogma(ScheduledExecutorService executor, HttpClient client, String accessToken) {
        super(executor);
        this.client = requireNonNull(client, "client");
        authorization = "Bearer " + requireNonNull(accessToken, "accessToken");
    }

    @Override
    public CompletableFuture<Void> createProject(String projectName) {
        try {
            validateProjectName(projectName);

            final ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("name", projectName);

            return client.execute(headers(HttpMethod.POST, PROJECTS_PREFIX), toBytes(root))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::createProject);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static Void createProject(AggregatedHttpMessage res) {
        if (res.status().code() == 201) { // Created
            return null;
        }
        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<Void> removeProject(String projectName) {
        try {
            validateProjectName(projectName);
            return client.execute(headers(HttpMethod.DELETE, pathBuilder(projectName).toString()))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::removeProject);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static Void removeProject(AggregatedHttpMessage res) {
        if (res.status().code() == 204) { // No Content
            return null;
        }
        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<Void> unremoveProject(String projectName) {
        try {
            validateProjectName(projectName);
            return client.execute(headers(HttpMethod.PATCH, pathBuilder(projectName).toString()),
                                  UNREMOVE_PATCH)
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::unremoveProject);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static Void unremoveProject(AggregatedHttpMessage res) {
        if (res.status().code() == 200) { // OK
            return null;
        }
        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<Set<String>> listProjects() {
        return client.execute(headers(HttpMethod.GET, PROJECTS_PREFIX))
                     .aggregate()
                     .thenApply(ArmeriaCentralDogma::handleNameList);
    }

    @Override
    public CompletableFuture<Set<String>> listRemovedProjects() {
        return client.execute(headers(HttpMethod.GET, PROJECTS_PREFIX + REMOVED_PARAM))
                     .aggregate()
                     .thenApply(ArmeriaCentralDogma::handleNameList);
    }

    @Override
    public CompletableFuture<Void> createRepository(String projectName, String repositoryName) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);

            final String path = pathBuilder(projectName).append(REPOS).toString();
            final ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("name", repositoryName);

            return client.execute(headers(HttpMethod.POST, path), toBytes(root))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::createRepository);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static Void createRepository(AggregatedHttpMessage res) {
        if (res.status().code() == 201) { // Created
            return null;
        }
        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<Void> removeRepository(String projectName, String repositoryName) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            return client.execute(headers(HttpMethod.DELETE,
                                          pathBuilder(projectName, repositoryName).toString()))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::removeRepository);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static Void removeRepository(AggregatedHttpMessage res) {
        if (res.status().code() == 204) { // No Content
            return null;
        }
        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<Void> unremoveRepository(String projectName, String repositoryName) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            return client.execute(headers(HttpMethod.PATCH,
                                          pathBuilder(projectName, repositoryName).toString()),
                                  UNREMOVE_PATCH)
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::handleSimpleResponse);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    @Override
    public CompletableFuture<Map<String, RepositoryInfo>> listRepositories(String projectName) {
        try {
            validateProjectName(projectName);
            return client.execute(headers(HttpMethod.GET, pathBuilder(projectName).append(REPOS).toString()))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::listRepositories);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static Map<String, RepositoryInfo> listRepositories(AggregatedHttpMessage res) {
        final int code = res.status().code();
        if (code == 200) { // OK
            return Streams.stream(toJson(res, JsonNodeType.ARRAY))
                          .map(node -> {
                              final String name = getField(node, "name").asText();
                              final Revision headRevision =
                                      new Revision(getField(node, "headRevision").asInt());
                              return new RepositoryInfo(name, headRevision);
                          })
                          .collect(toImmutableMap(RepositoryInfo::name, Function.identity()));
        }

        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<Set<String>> listRemovedRepositories(String projectName) {
        try {
            validateProjectName(projectName);
            return client.execute(headers(HttpMethod.GET,
                                          pathBuilder(projectName).append(REPOS)
                                                                  .append(REMOVED_PARAM).toString()))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::handleNameList);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    @Override
    public CompletableFuture<Revision> normalizeRevision(String projectName, String repositoryName,
                                                         Revision revision) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(revision, "revision");

            final String path = pathBuilder(projectName, repositoryName)
                    .append("/revision/")
                    .append(revision.text())
                    .toString();

            return client.execute(headers(HttpMethod.GET, path))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::normalizeRevision);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static Revision normalizeRevision(AggregatedHttpMessage res) {
        if (res.status().code() == 200) {
            return new Revision(getField(toJson(res, JsonNodeType.OBJECT), "revision").asInt());
        }
        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<Map<String, EntryType>> listFiles(String projectName, String repositoryName,
                                                               Revision revision, String pathPattern) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(revision, "revision");
            validatePathPattern(pathPattern, "pathPattern");

            final StringBuilder path = pathBuilder(projectName, repositoryName);
            path.append("/list");
            if (pathPattern.charAt(0) != '/') {
                path.append("/**/");
            }
            path.append(encodePathPattern(pathPattern));
            path.append("?revision=").append(revision.major());

            return client.execute(headers(HttpMethod.GET, path.toString()))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::listFiles);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static Map<String, EntryType> listFiles(AggregatedHttpMessage res) {
        if (res.status().code() == 200) {
            final ImmutableMap.Builder<String, EntryType> builder = ImmutableMap.builder();
            final JsonNode node = toJson(res, JsonNodeType.ARRAY);
            node.forEach(e -> builder.put(
                    getField(e, "path").asText(),
                    EntryType.valueOf(getField(e, "type").asText())));
            return builder.build();
        }

        return handleErrorResponse(res);
    }

    @Override
    public <T> CompletableFuture<Entry<T>> getFile(String projectName, String repositoryName, Revision revision,
                                                   Query<T> query) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(revision, "revision");
            requireNonNull(query, "query");

            return normalizeRevision(projectName, repositoryName, revision).thenCompose(normRev -> {
                final StringBuilder path = pathBuilder(projectName, repositoryName);
                path.append("/contents").append(query.path());
                path.append("?revision=").append(normRev.text());
                appendJsonPaths(path, query.type(), query.expressions());

                return client.execute(headers(HttpMethod.GET, path.toString()))
                             .aggregate()
                             .thenApply(res -> getFile(normRev, res));
            });
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static <T> Entry<T> getFile(Revision normRev, AggregatedHttpMessage res) {
        if (res.status().code() == 200) {
            final JsonNode node = toJson(res, JsonNodeType.OBJECT);
            return toEntry(normRev, node);
        }

        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<Map<String, Entry<?>>> getFiles(String projectName, String repositoryName,
                                                             Revision revision, String pathPattern) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(revision, "revision");
            validatePathPattern(pathPattern, "pathPattern");

            return normalizeRevision(projectName, repositoryName, revision).thenCompose(normRev -> {
                final StringBuilder path = pathBuilder(projectName, repositoryName);
                path.append("/contents");
                if (pathPattern.charAt(0) != '/') {
                    path.append("/**/");
                }
                path.append(encodePathPattern(pathPattern));
                path.append("?revision=").append(normRev.major());

                return client.execute(headers(HttpMethod.GET, path.toString()))
                             .aggregate()
                             .thenApply(res -> getFiles(normRev, res));
            });
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static Map<String, Entry<?>> getFiles(Revision normRev, AggregatedHttpMessage res) {
        if (res.status().code() == 200) {
            final JsonNode node = toJson(res, null);
            final ImmutableMap.Builder<String, Entry<?>> builder = ImmutableMap.builder();
            if (node.isObject()) { // Single entry
                final Entry<?> entry = toEntry(normRev, node);
                builder.put(entry.path(), entry);
            } else if (node.isArray()) { // Multiple entries
                node.forEach(e -> {
                    final Entry<?> entry = toEntry(normRev, e);
                    builder.put(entry.path(), entry);
                });
            } else {
                return rejectNeitherArrayNorObject(res);
            }
            return builder.build();
        }

        return handleErrorResponse(res);
    }

    @Override
    public <T> CompletableFuture<MergedEntry<T>> mergeFiles(String projectName, String repositoryName,
                                                            Revision revision, MergeQuery<T> mergeQuery) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(revision, "revision");
            requireNonNull(mergeQuery, "mergeQuery");

            final StringBuilder path = pathBuilder(projectName, repositoryName);
            path.append("/merge?revision=").append(revision.major());
            mergeQuery.mergeSources().forEach(
                    src -> path.append(src.isOptional() ? "&optional_path=" : "&path=")
                               .append(encodeParam(src.path())));
            appendJsonPaths(path, mergeQuery.type(), mergeQuery.expressions());
            return client.execute(headers(HttpMethod.GET, path.toString()))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::mergeFiles);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static <T> MergedEntry<T> mergeFiles(AggregatedHttpMessage res) {
        if (res.status().code() == 200) {
            final JsonNode node = toJson(res, JsonNodeType.OBJECT);

            // Build path list.
            final ImmutableList.Builder<String> pathsBuilder = ImmutableList.builder();
            for (JsonNode path : getField(node, "paths")) {
                if (path.getNodeType() != JsonNodeType.STRING) {
                    throw new CentralDogmaException("Received a merged entry with a non-string path: " + node);
                }
                pathsBuilder.add(path.asText());
            }
            final List<String> paths = pathsBuilder.build();
            if (paths.isEmpty()) {
                throw new CentralDogmaException("Received a merged entry with empty paths: " + node);
            }

            // Build the merged entry.
            final Revision revision = new Revision(getField(node, "revision").asInt());
            final EntryType type = EntryType.valueOf(getField(node, "type").asText());
            final JsonNode content = getField(node, "content");
            switch (type) {
                case JSON: {
                    @SuppressWarnings("unchecked")
                    final MergedEntry<T> cast =
                            (MergedEntry<T>) MergedEntry.of(revision, type, content, paths);
                    return cast;
                }
                case TEXT: {
                    if (content.getNodeType() != JsonNodeType.STRING) {
                        throw new CentralDogmaException(
                                "Received a TEXT merged entry whose content is not a string: " + node);
                    }
                    @SuppressWarnings("unchecked")
                    final MergedEntry<T> cast =
                            (MergedEntry<T>) MergedEntry.of(revision, type, content.asText(), paths);
                    return cast;
                }
                default:
                    throw new CentralDogmaException(
                            "Received a merged entry whose type is neither JSON nor TEXT: " + node);
            }
        }

        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<List<Commit>> getHistory(String projectName, String repositoryName,
                                                      Revision from, Revision to,
                                                      String pathPattern) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(from, "from");
            requireNonNull(to, "to");
            validatePathPattern(pathPattern, "pathPattern");

            final StringBuilder path = pathBuilder(projectName, repositoryName);
            path.append("/commits/").append(from.text());
            path.append("?to=").append(to.text());
            path.append("&path=").append(encodeParam(pathPattern));

            return client.execute(headers(HttpMethod.GET, path.toString()))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::getHistory);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static List<Commit> getHistory(AggregatedHttpMessage res) {
        if (res.status().code() == 200) {
            final JsonNode node = toJson(res, null);
            if (node.isObject()) {
                return ImmutableList.of(toCommit(node));
            } else if (node.isArray()) {
                return Streams.stream(node)
                              .map(ArmeriaCentralDogma::toCommit)
                              .collect(toImmutableList());
            } else {
                return rejectNeitherArrayNorObject(res);
            }
        }

        return handleErrorResponse(res);
    }

    @Override
    public <T> CompletableFuture<Change<T>> getDiff(String projectName, String repositoryName, Revision from,
                                                    Revision to, Query<T> query) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(from, "from");
            requireNonNull(to, "to");
            requireNonNull(query, "query");

            final StringBuilder path = pathBuilder(projectName, repositoryName);
            path.append("/compare");
            path.append("?path=").append(encodeParam(query.path()));
            path.append("&from=").append(from.text());
            path.append("&to=").append(to.text());
            appendJsonPaths(path, query.type(), query.expressions());

            return client.execute(headers(HttpMethod.GET, path.toString()))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::getDiff);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static <T> Change<T> getDiff(AggregatedHttpMessage res) {
        if (res.status().code() == 200) {
            return toChange(toJson(res, JsonNodeType.OBJECT));
        }
        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<List<Change<?>>> getDiffs(String projectName, String repositoryName, Revision from,
                                                       Revision to, String pathPattern) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(from, "from");
            requireNonNull(to, "to");
            validatePathPattern(pathPattern, "pathPattern");

            final StringBuilder path = pathBuilder(projectName, repositoryName);
            path.append("/compare");
            path.append("?pathPattern=").append(encodeParam(pathPattern));
            path.append("&from=").append(from.text());
            path.append("&to=").append(to.text());

            return client.execute(headers(HttpMethod.GET, path.toString()))
                         .aggregate()
                         .thenApply(res -> {
                             if (res.status().code() == 200) {
                                 final JsonNode node = toJson(res, null);
                                 if (node.isObject()) {
                                     return ImmutableList.of(toChange(node));
                                 } else if (node.isArray()) {
                                     return Streams.stream(node)
                                                   .map(ArmeriaCentralDogma::toChange)
                                                   .collect(toImmutableList());
                                 } else {
                                     return rejectNeitherArrayNorObject(res);
                                 }
                             }

                             return handleErrorResponse(res);
                         });
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    @Override
    public CompletableFuture<List<Change<?>>> getPreviewDiffs(String projectName, String repositoryName,
                                                              Revision baseRevision,
                                                              Iterable<? extends Change<?>> changes) {
        try {
            try {
                validateProjectAndRepositoryName(projectName, repositoryName);
                requireNonNull(baseRevision, "baseRevision");
                requireNonNull(changes, "changes");

                final String path = pathBuilder(projectName, repositoryName)
                        .append("/preview?revision=")
                        .append(baseRevision.text())
                        .toString();

                final ArrayNode changesNode = toJson(changes);
                return client.execute(headers(HttpMethod.POST, path), toBytes(changesNode))
                             .aggregate()
                             .thenApply(ArmeriaCentralDogma::getPreviewDiffs);
            } catch (Exception e) {
                return exceptionallyCompletedFuture(e);
            }
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static List<Change<?>> getPreviewDiffs(AggregatedHttpMessage res) {
        switch (res.status().code()) {
            case 200:
                final JsonNode node = toJson(res, JsonNodeType.ARRAY);
                final ImmutableList.Builder<Change<?>> builder = ImmutableList.builder();
                node.forEach(e -> builder.add(toChange(e)));
                return builder.build();
            case 204:
                return ImmutableList.of();
        }

        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                              String summary, String detail, Markup markup,
                                              Iterable<? extends Change<?>> changes) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(baseRevision, "baseRevision");
            requireNonNull(summary, "summary");
            checkArgument(!summary.isEmpty(), "summary is empty.");
            requireNonNull(markup, "markup");
            requireNonNull(changes, "changes");
            checkArgument(!Iterables.isEmpty(changes), "changes is empty.");

            final String path = pathBuilder(projectName, repositoryName)
                    .append("/contents?revision=")
                    .append(baseRevision.text())
                    .toString();

            final ObjectNode commitNode = JsonNodeFactory.instance.objectNode();
            commitNode.set("commitMessage",
                           JsonNodeFactory.instance.objectNode()
                                                   .put("summary", summary)
                                                   .put("detail", detail)
                                                   .put("markup", markup.name()));
            commitNode.set("changes", toJson(changes));

            return client.execute(headers(HttpMethod.POST, path), toBytes(commitNode))
                         .aggregate()
                         .thenApply(ArmeriaCentralDogma::push);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    private static PushResult push(AggregatedHttpMessage res) {
        if (res.status().code() == 200) {
            final JsonNode node = toJson(res, JsonNodeType.OBJECT);
            return new PushResult(
                    new Revision(getField(node, "revision").asInt()),
                    Instant.parse(getField(node, "pushedAt").asText()).toEpochMilli());
        }

        return handleErrorResponse(res);
    }

    @Override
    public CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                              Author author, String summary, String detail, Markup markup,
                                              Iterable<? extends Change<?>> changes) {
        // The author specified by the client will be ignored.
        // The server will determine the author.
        return push(projectName, repositoryName, baseRevision, summary, detail, markup, changes);
    }

    @Override
    public CompletableFuture<Revision> watchRepository(String projectName, String repositoryName,
                                                       Revision lastKnownRevision, String pathPattern,
                                                       long timeoutMillis) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(lastKnownRevision, "lastKnownRevision");
            validatePathPattern(pathPattern, "pathPattern");
            checkArgument(timeoutMillis > 0, "timeoutMillis: %s (expected: > 0)", timeoutMillis);

            final StringBuilder path = pathBuilder(projectName, repositoryName);
            path.append("/contents");
            if (pathPattern.charAt(0) != '/') {
                path.append("/**/");
            }
            path.append(encodePathPattern(pathPattern));

            return watch(lastKnownRevision, timeoutMillis, path.toString(),
                         ArmeriaCentralDogma::watchRepository);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    @Nullable
    private static Revision watchRepository(AggregatedHttpMessage res) {
        switch (res.status().code()) {
            case 200: // OK
                final JsonNode node = toJson(res, JsonNodeType.OBJECT);
                return new Revision(getField(node, "revision").asInt());
            case 304: // Not Modified
                return null;
        }

        return handleErrorResponse(res);
    }

    @Override
    public <T> CompletableFuture<Entry<T>> watchFile(String projectName, String repositoryName,
                                                     Revision lastKnownRevision, Query<T> query,
                                                     long timeoutMillis) {
        try {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(lastKnownRevision, "lastKnownRevision");
            requireNonNull(query, "query");
            checkArgument(timeoutMillis > 0, "timeoutMillis: %s (expected: > 0)", timeoutMillis);

            final StringBuilder path = pathBuilder(projectName, repositoryName);
            path.append("/contents").append(query.path());
            if (query.type() == QueryType.JSON_PATH) {
                path.append('?');
                query.expressions().forEach(expr -> path.append("jsonpath=").append(encodeParam(expr))
                                                        .append('&'));

                // Remove the trailing '?' or '&'.
                path.setLength(path.length() - 1);
            }

            return watch(lastKnownRevision, timeoutMillis, path.toString(), ArmeriaCentralDogma::watchFile);
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        }
    }

    @Nullable
    private static <T> Entry<T> watchFile(AggregatedHttpMessage res) {
        switch (res.status().code()) {
            case 200: // OK
                final JsonNode node = toJson(res, JsonNodeType.OBJECT);
                final Revision revision = new Revision(getField(node, "revision").asInt());
                return toEntry(revision, getField(node, "entry"));
            case 304: // Not Modified
                return null;
        }

        return handleErrorResponse(res);
    }

    private <T> CompletableFuture<T> watch(Revision lastKnownRevision, long timeoutMillis,
                                           String path, Function<AggregatedHttpMessage, T> func) {
        final HttpHeaders headers = headers(HttpMethod.GET, path)
                .set(HttpHeaderNames.IF_NONE_MATCH, lastKnownRevision.text())
                .set(HttpHeaderNames.PREFER, "wait=" + LongMath.saturatedAdd(timeoutMillis, 999) / 1000L);

        try (SafeCloseable ignored = Clients.withContextCustomizer(ctx -> {
            ctx.setResponseTimeoutMillis(
                    WatchTimeout.makeReasonable(timeoutMillis, ctx.responseTimeoutMillis()));
        })) {
            return client.execute(headers).aggregate().thenApply(func);
        }
    }

    private static void validateProjectName(String projectName) {
        Util.validateProjectName(projectName, "projectName");
    }

    private static void validateProjectAndRepositoryName(String projectName, String repositoryName) {
        validateProjectName(projectName);
        Util.validateRepositoryName(repositoryName, "repositoryName");
    }

    private HttpHeaders headers(HttpMethod method, String path) {
        final HttpHeaders headers = HttpHeaders.of(method, path);
        headers.set(HttpHeaderNames.AUTHORIZATION, authorization);
        headers.setObject(HttpHeaderNames.ACCEPT, MediaType.JSON);

        switch (method) {
            case POST:
            case PUT:
                headers.contentType(MediaType.JSON_UTF_8);
                break;
            case PATCH:
                headers.contentType(JSON_PATCH_UTF8);
                break;
        }

        return headers;
    }

    private static StringBuilder pathBuilder(String projectName) {
        return new StringBuilder().append(PROJECTS_PREFIX).append('/').append(projectName);
    }

    private static StringBuilder pathBuilder(String projectName, String repositoryName) {
        return pathBuilder(projectName).append(REPOS).append('/').append(repositoryName);
    }

    private static void appendJsonPaths(StringBuilder path, QueryType queryType, Iterable<String> expressions) {
        if (queryType == QueryType.JSON_PATH) {
            expressions.forEach(expr -> path.append("&jsonpath=").append(encodeParam(expr)));
        }
    }

    @SuppressWarnings("CharsetObjectCanBeUsed") // We target Java 8.
    private static String encodeParam(String param) {
        try {
            return URLEncoder.encode(param, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new Error(); // Never reaches here.
        }
    }

    private static String encodePathPattern(String pathPattern) {
        return UrlEscapers.urlFragmentEscaper().escape(pathPattern);
    }

    /**
     * Encodes the specified {@link JsonNode} into a byte array.
     */
    private static byte[] toBytes(JsonNode content) {
        try {
            return Jackson.writeValueAsBytes(content);
        } catch (JsonProcessingException e) {
            // Should never reach here.
            throw new Error(e);
        }
    }

    /**
     * Encodes a list of {@link Change}s into a JSON array.
     */
    private static ArrayNode toJson(Iterable<? extends Change<?>> changes) {
        final ArrayNode changesNode = JsonNodeFactory.instance.arrayNode();
        changes.forEach(c -> {
            final ObjectNode changeNode = JsonNodeFactory.instance.objectNode();
            changeNode.put("path", c.path());
            changeNode.put("type", c.type().name());
            final Class<?> contentType = c.type().contentType();
            if (contentType == JsonNode.class) {
                changeNode.set("content", (JsonNode) c.content());
            } else if (contentType == String.class) {
                changeNode.put("content", (String) c.content());
            }
            changesNode.add(changeNode);
        });
        return changesNode;
    }

    /**
     * Parses the content of the specified {@link AggregatedHttpMessage} into a {@link JsonNode}.
     */
    private static JsonNode toJson(AggregatedHttpMessage res, @Nullable JsonNodeType expectedNodeType) {
        final String content = toString(res);
        final JsonNode node;
        try {
            node = Jackson.readTree(content);
        } catch (JsonParseException e) {
            throw new CentralDogmaException("failed to parse the response JSON", e);
        }

        if (expectedNodeType != null && node.getNodeType() != expectedNodeType) {
            throw new CentralDogmaException(
                    "invalid server response; expected: " + expectedNodeType +
                    ", actual: " + node.getNodeType() + ", content: " + content);
        }
        return node;
    }

    private static <T> T rejectNeitherArrayNorObject(AggregatedHttpMessage res) {
        throw new CentralDogmaException(
                "invalid server response; expected: " + JsonNodeType.OBJECT + " or " + JsonNodeType.ARRAY +
                ", content: " + toString(res));
    }

    private static String toString(AggregatedHttpMessage res) {
        final MediaType contentType = firstNonNull(res.headers().contentType(), MediaType.JSON_UTF_8);
        final Charset charset = contentType.charset().orElse(StandardCharsets.UTF_8);
        return res.content(charset);
    }

    private static <T> Entry<T> toEntry(Revision revision, JsonNode node) {
        final String entryPath = getField(node, "path").asText();
        final EntryType entryType = EntryType.valueOf(getField(node, "type").asText());
        switch (entryType) {
            case JSON:
                return unsafeCast(Entry.ofJson(revision, entryPath, getField(node, "content")));
            case TEXT:
                return unsafeCast(Entry.ofText(revision, entryPath,
                                               getField(node, "content").asText()));
            case DIRECTORY:
                return unsafeCast(Entry.ofDirectory(revision, entryPath));
        }
        throw new Error(); // Never reaches here.
    }

    private static Commit toCommit(JsonNode node) {
        final Revision revision = new Revision(getField(node, "revision").asInt());
        final JsonNode authorNode = getField(node, "author");
        final Author author = new Author(getField(authorNode, "name").asText(),
                                         getField(authorNode, "email").asText());
        final long pushedAt = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(
                getField(node, "pushedAt").asText())).toEpochMilli();
        final JsonNode commitMessageNode = getField(node, "commitMessage");
        final String summary = getField(commitMessageNode, "summary").asText();
        final String detail = getField(commitMessageNode, "detail").asText();
        final Markup markup = Markup.valueOf(getField(commitMessageNode, "markup").asText());
        return new Commit(revision, author, pushedAt, summary, detail, markup);
    }

    private static <T> Change<T> toChange(JsonNode node) {
        final String actualPath = getField(node, "path").asText();
        final ChangeType type = ChangeType.valueOf(getField(node, "type").asText());
        switch (type) {
            case UPSERT_JSON:
                return unsafeCast(Change.ofJsonUpsert(actualPath, getField(node, "content")));
            case UPSERT_TEXT:
                return unsafeCast(Change.ofTextUpsert(actualPath, getField(node, "content").asText()));
            case REMOVE:
                return unsafeCast(Change.ofRemoval(actualPath));
            case RENAME:
                return unsafeCast(Change.ofRename(actualPath, getField(node, "content").asText()));
            case APPLY_JSON_PATCH:
                return unsafeCast(Change.ofJsonPatch(actualPath, getField(node, "content")));
            case APPLY_TEXT_PATCH:
                return unsafeCast(Change.ofTextPatch(actualPath, getField(node, "content").asText()));
        }

        throw new Error(); // Never reaches here.
    }

    private static Set<String> handleNameList(AggregatedHttpMessage res) {
        final int code = res.status().code();
        if (code == 200) { // OK
            return Streams.stream(toJson(res, JsonNodeType.ARRAY))
                          .map(node -> getField(node, "name").asText())
                          .collect(toImmutableSet());
        }
        return handleErrorResponse(res);
    }

    private static JsonNode getField(JsonNode node, String fieldName) {
        final JsonNode field = node.get(fieldName);
        if (field == null) {
            throw new CentralDogmaException(
                    "invalid server response; field '" + fieldName + "' does not exist: " + node);
        }
        return field;
    }

    @Nullable
    private static <T> T handleSimpleResponse(AggregatedHttpMessage res) {
        final HttpStatus status = res.status();
        assert status != null : res;
        if (status.code() == 200) {
            return null;
        }

        return handleErrorResponse(res);
    }

    private static <T> T handleErrorResponse(AggregatedHttpMessage res) {
        final HttpStatus status = res.status();
        assert status != null : res;
        if (status.codeClass() != HttpStatusClass.SUCCESS) {
            final JsonNode node = toJson(res, JsonNodeType.OBJECT);
            final JsonNode exceptionNode = node.get("exception");
            final JsonNode messageNode = node.get("message");

            if (exceptionNode != null) {
                final String typeName = exceptionNode.textValue();
                if (typeName != null) {
                    final Function<String, CentralDogmaException> exceptionFactory =
                            EXCEPTION_FACTORIES.get(typeName);
                    if (exceptionFactory != null) {
                        throw exceptionFactory.apply(messageNode.textValue());
                    }
                }
            }
        }

        throw new CentralDogmaException("unexpected response: " + res.headers() + ", " + res.contentUtf8());
    }
}
