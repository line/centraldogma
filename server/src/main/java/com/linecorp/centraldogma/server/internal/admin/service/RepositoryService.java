/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.admin.service;

import static com.spotify.futures.CompletableFutures.allAsList;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryType;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil;
import com.linecorp.centraldogma.server.internal.admin.dto.ChangeDto;
import com.linecorp.centraldogma.server.internal.admin.dto.CommitDto;
import com.linecorp.centraldogma.server.internal.admin.dto.CommitMessageDto;
import com.linecorp.centraldogma.server.internal.admin.dto.EntryDto;
import com.linecorp.centraldogma.server.internal.admin.dto.EntryWithRevisionDto;
import com.linecorp.centraldogma.server.internal.admin.dto.RepositoryDto;
import com.linecorp.centraldogma.server.internal.admin.dto.RevisionDto;
import com.linecorp.centraldogma.server.internal.admin.exception.BadRequestException;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.SafeProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.FindOption;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * Annotated service object for managing repositories.
 */
public class RepositoryService extends AbstractService {

    private static final Map<FindOption<?>, Object> LIST_FILES_FIND_OPTIONS = new IdentityHashMap<>();

    private static final Object VOID = new Object();

    private static final Splitter termSplitter = Splitter.on(',').trimResults().omitEmptyStrings();

    public RepositoryService(ProjectManager projectManager,
                             CommandExecutor executor) {
        super(new SafeProjectManager(projectManager), executor);
    }

    /**
     * GET /projects/{projectName}/repositories
     * Returns the list of the repositories.
     */
    @Get("/projects/{projectName}/repositories")
    public CompletionStage<List<RepositoryDto>> listRepositories(@Param("projectName") String projectName) {
        return allAsList(projectManager().get(projectName).repos().list()
                                         .values().stream().map(DtoConverter::convert)
                                         .collect(toList()));
    }

    /**
     * POST /projects/{projectName}/repositories
     * Creates a new repository.
     */
    @Post("/projects/{projectName}/repositories")
    public CompletionStage<RepositoryDto> createRepository(@Param("projectName") String projectName,
                                                           AggregatedHttpMessage message) throws IOException {

        final Author author = AuthenticationUtil.currentAuthor();
        final RepositoryDto dto =
                Jackson.readValue(message.content().toStringAscii(), RepositoryDto.class);
        return execute(Command.createRepository(author, projectName, dto.getName()))
                .thenApply(unused -> dto);
    }

    /**
     * GET /projects/{projectName}/repositories/{repository}/revision/{revision}
     * Normalizes the revision into an absolute revision.
     */
    @Get("/projects/{projectName}/repositories/{repositoryName}/revision/{revision}")
    public CompletionStage<RevisionDto> normalizeRevision(@Param("projectName") String projectName,
                                                          @Param("repositoryName") String repositoryName,
                                                          @Param("revision") String revision) {
        return projectManager().get(projectName).repos().get(repositoryName)
                               .normalize(new Revision(revision))
                               .thenApply(DtoConverter::convert);
    }

    /**
     * GET /projects/{projectName}/repositories/{repository}/tree/revisions/{revision}{path}
     * Returns the list of files in the path.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repositories/(?<repositoryName>[^/]+)" +
         "/tree/revisions/(?<revision>[^/]+)(?<path>/.*$)")
    public CompletionStage<List<EntryDto>> getTree(@Param("projectName") String projectName,
                                                   @Param("repositoryName") String repositoryName,
                                                   @Param("revision") String revision,
                                                   @Param("path") String path) {
        return listFiles(projectName, repositoryName, new Revision(revision), path);
    }

    /**
     * GET /projects/{projectName}/repositories/{repository}/files/revisions/{revision}{path}
     * Returns the blob in the path.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repositories/(?<repositoryName>[^/]+)" +
         "/files/revisions/(?<revision>[^/]+)(?<path>/.*$)")
    public CompletionStage<EntryWithRevisionDto> getFile(@Param("projectName") String projectName,
                                                         @Param("repositoryName") String repositoryName,
                                                         @Param("revision") String revision,
                                                         @Param("path") String path,
                                                         @Param("queryType") Optional<String> queryType,
                                                         @Param("expression") Optional<String> expressions) {

        final Query<?> query = Query.of(QueryType.valueOf(queryType.orElse("IDENTITY")),
                                        path, expressions.orElse(""));
        final Repository repo = projectManager().get(projectName).repos().get(repositoryName);
        return repo.normalize(new Revision(revision))
                   .thenCompose(normalized -> repo.get(normalized, query))
                   .thenApply(queryResult -> DtoConverter.convert(path, queryResult));
    }

    /**
     * POST|PUT /projects/{projectName}/repositories/{repository}/files/revisions/{revision}
     * Adds a new file or edits the existing file.
     */
    @Post
    @Put
    @Path("/projects/{projectName}/repositories/{repositoryName}/files/revisions/{revision}")
    public CompletionStage<Object> addOrEditFile(@Param("projectName") String projectName,
                                                 @Param("repositoryName") String repositoryName,
                                                 @Param("revision") String revision,
                                                 AggregatedHttpMessage message,
                                                 ServiceRequestContext ctx) {
        final Entry<CommitMessageDto, Change<?>> p = commitMessageAndChange(message);
        final CommitMessageDto commitMessage = p.getKey();
        final Change<?> change = p.getValue();
        return push(projectName, repositoryName, new Revision(revision), AuthenticationUtil.currentAuthor(ctx),
                    commitMessage.getSummary(), commitMessage.getDetail().getContent(),
                    Markup.valueOf(commitMessage.getDetail().getMarkup()), change)
                // This is so weird but there is no way to find a converter for 'null' with the current
                // Armeria's converter implementation. We will figure out a better way to improve it.
                .thenApply(unused -> VOID);
    }

    /**
     * POST /projects/{projectName}/repositories/{repository}/delete/revisions/{revision}{path}
     * Deletes a file.
     */
    @Post("regex:/projects/(?<projectName>[^/]+)/repositories/(?<repositoryName>[^/]+)" +
          "/delete/revisions/(?<revision>[^/]+)(?<path>/.*$)")
    public DefaultHttpResponse deleteFile(@Param("projectName") String projectName,
                                          @Param("repositoryName") String repositoryName,
                                          @Param("revision") String revision,
                                          @Param("path") String path,
                                          AggregatedHttpMessage message,
                                          ServiceRequestContext ctx) {
        final CommitMessageDto commitMessage;
        try {
            final JsonNode node = Jackson.readTree(message.content().toStringAscii());
            commitMessage = Jackson.convertValue(node.get("commitMessage"), CommitMessageDto.class);
        } catch (IOException e) {
            throw new BadRequestException("invalid data to be parsed", e);
        }

        final DefaultHttpResponse response = new DefaultHttpResponse();
        push(projectName, repositoryName, new Revision(revision), AuthenticationUtil.currentAuthor(ctx),
             commitMessage.getSummary(), commitMessage.getDetail().getContent(),
             Markup.valueOf(commitMessage.getDetail().getMarkup()), Change.ofRemoval(path))
                .whenComplete((unused, cause) -> {
                    if (cause == null) {
                        response.respond(HttpStatus.OK);
                    } else {
                        response.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                });
        return response;
    }

    /**
     * GET /projects/{projectName}/repositories/{repositoryName}/history{path}?from=x.x&amp;to=x.x
     * Returns a history between the specified revisions.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repositories/(?<repositoryName>[^/]+)" +
         "/history(?<path>/.*$)")
    public CompletionStage<List<CommitDto>> getHistory(@Param("projectName") String projectName,
                                                       @Param("repositoryName") String repositoryName,
                                                       @Param("path") String path,
                                                       @Param("from") Optional<String> from,
                                                       @Param("to") Optional<String> to) {
        return projectManager().get(projectName).repos().get(repositoryName)
                               .history(new Revision(from.orElse("-1")),
                                        new Revision(to.orElse("1")),
                                        path + "**")
                               .thenApply(commits -> commits.stream()
                                                            .map(DtoConverter::convert)
                                                            .collect(toList()));
    }

    /**
     * GET /projects/{projectName}/repositories/{repositoryName}/search/revisions/{revision}?term={term}
     * Finds the files matched by {@code term}.
     */
    @Get("/projects/{projectName}/repositories/{repositoryName}/search/revisions/{revision}")
    public CompletionStage<List<EntryDto>> search(@Param("projectName") String projectName,
                                                  @Param("repositoryName") String repositoryName,
                                                  @Param("revision") String revision,
                                                  @Param("term") String term) {
        return listFiles(projectName, repositoryName, new Revision(revision), normalizeSearchTerm(term));
    }

    /**
     * GET /projects/{projectName}/repositories/{repository}/diff{path}?from={from}&amp;to={to}
     * Returns a diff of the specified path between the specified revisions.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repositories/(?<repositoryName>[^/]+)" +
         "/diff(?<path>/.*$)")
    public CompletionStage<List<ChangeDto>> getDiff(@Param("projectName") String projectName,
                                                    @Param("repositoryName") String repositoryName,
                                                    @Param("path") String path,
                                                    @Param("from") String from,
                                                    @Param("to") String to) {
        return projectManager().get(projectName).repos().get(repositoryName)
                               .diff(new Revision(from), new Revision(to), path)
                               .thenApply(changeMap -> changeMap.values().stream()
                                                                .map(DtoConverter::convert)
                                                                .collect(toList()));
    }

    private CompletableFuture<?> push(String projectName, String repositoryName,
                                      Revision revision, Author author,
                                      String commitSummary, String commitDetail, Markup commitMarkup,
                                      Change<?> change) {
        return projectManager().get(projectName).repos().get(repositoryName)
                               .normalize(revision)
                               .thenCompose(normalizedRevision ->
                                                    push0(projectName, repositoryName, revision, author,
                                                          commitSummary, commitDetail, commitMarkup, change));
    }

    private CompletableFuture<?> push0(String projectName, String repositoryName,
                                       Revision normalizedRev, Author author,
                                       String commitSummary, String commitDetail, Markup commitMarkup,
                                       Change<?> change) {
        final CompletableFuture<Map<String, Change<?>>> f = normalizeChanges(
                projectManager(), projectName, repositoryName, normalizedRev, ImmutableList.of(change));

        return f.thenCompose(
                changes -> execute(Command.push(author, projectName, repositoryName, normalizedRev,
                                                commitSummary, commitDetail, commitMarkup, changes.values())));
    }

    private CompletionStage<List<EntryDto>> listFiles(String projectName,
                                                      String repositoryName,
                                                      Revision revision,
                                                      String pathPattern) {
        pathPattern += pathPattern.endsWith("/") ? '*' : "/*";
        return projectManager().get(projectName).repos().get(repositoryName)
                               .find(revision, pathPattern, LIST_FILES_FIND_OPTIONS)
                               .thenApply(
                                       entries -> entries.values().stream()
                                                         .map(DtoConverter::convert)
                                                         .collect(toList()));
    }

    private static Entry<CommitMessageDto, Change<?>> commitMessageAndChange(AggregatedHttpMessage message) {
        try {
            final JsonNode node = Jackson.readTree(message.content().toStringAscii());
            final CommitMessageDto commitMessage =
                    Jackson.convertValue(node.get("commitMessage"), CommitMessageDto.class);
            final EntryDto file = Jackson.convertValue(node.get("file"), EntryDto.class);
            final Change<?> change;
            switch (file.getType()) {
                case "JSON":
                    change = Change.ofJsonUpsert(file.getPath(), file.getContent());
                    break;
                case "TEXT":
                    change = Change.ofTextUpsert(file.getPath(), file.getContent());
                    break;
                default:
                    throw new IllegalArgumentException("unsupported file type: " + file.getType());
            }

            return Maps.immutableEntry(commitMessage, change);
        } catch (IOException e) {
            throw new BadRequestException("invalid data to be parsed", e);
        }
    }

    private static CompletableFuture<Map<String, Change<?>>> normalizeChanges(
            ProjectManager projectManager, String projectName, String repositoryName, Revision baseRevision,
            Iterable<Change<?>> changes) {
        return projectManager.get(projectName).repos().get(repositoryName)
                             .previewDiff(baseRevision, changes);
    }

    private static String normalizeSearchTerm(final String term) {
        if (Strings.isNullOrEmpty(term)) {
            throw new IllegalArgumentException("term should not be empty");
        }

        final StringBuilder sb = new StringBuilder();

        for (final String term0 : termSplitter.split(term)) {
            if (sb.length() > 0) {
                sb.append(',');
            }

            if (term0.matches(".*[/\\*]+.*")) {
                sb.append(term0);
            } else {
                sb.append('*').append(term0).append('*');
            }
        }
        return sb.toString();
    }
}
