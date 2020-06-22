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

import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ALL_WITH_CONTENT;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
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
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.admin.dto.ChangeDto;
import com.linecorp.centraldogma.server.internal.admin.dto.CommitDto;
import com.linecorp.centraldogma.server.internal.admin.dto.CommitMessageDto;
import com.linecorp.centraldogma.server.internal.admin.dto.EntryDto;
import com.linecorp.centraldogma.server.internal.admin.dto.RevisionDto;
import com.linecorp.centraldogma.server.internal.api.AbstractService;
import com.linecorp.centraldogma.server.internal.api.HttpApiExceptionHandler;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresReadPermission;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresWritePermission;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * Annotated service object for managing repositories.
 */
@RequiresReadPermission
@ExceptionHandler(HttpApiExceptionHandler.class)
public class RepositoryService extends AbstractService {

    private static final Object VOID = new Object();

    private static final Splitter termSplitter = Splitter.on(',').trimResults().omitEmptyStrings();

    public RepositoryService(ProjectManager projectManager, CommandExecutor executor) {
        super(projectManager, executor);
    }

    /**
     * GET /projects/{projectName}/repositories/{repoName}/revision/{revision}
     * Normalizes the revision into an absolute revision.
     */
    @Get("/projects/{projectName}/repositories/{repoName}/revision/{revision}")
    public RevisionDto normalizeRevision(@Param String projectName,
                                         @Param String repoName,
                                         @Param String revision) {
        return DtoConverter.convert(projectManager().get(projectName).repos().get(repoName)
                                                    .normalizeNow(new Revision(revision)));
    }

    /**
     * GET /projects/{projectName}/repositories/{repoName}/files/revisions/{revision}{path}
     * Returns the blob in the path.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repositories/(?<repoName>[^/]+)" +
         "/files/revisions/(?<revision>[^/]+)(?<path>/.*$)")
    public CompletionStage<EntryDto> getFile(@Param String projectName,
                                             @Param String repoName,
                                             @Param String revision,
                                             @Param String path,
                                             @Param @Default("IDENTITY") QueryType queryType,
                                             @Param @Default("") String expression) {

        final Query<?> query = Query.of(queryType,path, expression);
        final Repository repo = projectManager().get(projectName).repos().get(repoName);
        return repo.get(repo.normalizeNow(new Revision(revision)), query)
                   .thenApply(DtoConverter::convert);
    }

    /**
     * POST|PUT /projects/{projectName}/repositories/{repoName}/files/revisions/{revision}
     * Adds a new file or edits the existing file.
     */
    @Post
    @Put
    @Path("/projects/{projectName}/repositories/{repoName}/files/revisions/{revision}")
    @RequiresWritePermission
    public CompletionStage<Object> addOrEditFile(@Param String projectName,
                                                 @Param String repoName,
                                                 @Param String revision,
                                                 AggregatedHttpRequest request,
                                                 ServiceRequestContext ctx) {
        final Entry<CommitMessageDto, Change<?>> p = commitMessageAndChange(request);
        final CommitMessageDto commitMessage = p.getKey();
        final Change<?> change = p.getValue();
        return push(projectName, repoName, new Revision(revision), AuthUtil.currentAuthor(ctx),
                    commitMessage.getSummary(), commitMessage.getDetail().getContent(),
                    Markup.valueOf(commitMessage.getDetail().getMarkup()), change)
                // This is so weird but there is no way to find a converter for 'null' with the current
                // Armeria's converter implementation. We will figure out a better way to improve it.
                .thenApply(unused -> VOID);
    }

    /**
     * POST /projects/{projectName}/repositories/{repoName}/delete/revisions/{revision}{path}
     * Deletes a file.
     */
    @Post("regex:/projects/(?<projectName>[^/]+)/repositories/(?<repoName>[^/]+)" +
          "/delete/revisions/(?<revision>[^/]+)(?<path>/.*$)")
    @RequiresWritePermission
    public HttpResponse deleteFile(@Param String projectName,
                                   @Param String repoName,
                                   @Param String revision,
                                   @Param String path,
                                   AggregatedHttpRequest request,
                                   ServiceRequestContext ctx) {
        final CommitMessageDto commitMessage;
        try {
            final JsonNode node = Jackson.readTree(request.contentUtf8());
            commitMessage = Jackson.convertValue(node.get("commitMessage"), CommitMessageDto.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid data to be parsed", e);
        }

        final CompletableFuture<?> future =
                push(projectName, repoName, new Revision(revision), AuthUtil.currentAuthor(ctx),
                     commitMessage.getSummary(), commitMessage.getDetail().getContent(),
                     Markup.valueOf(commitMessage.getDetail().getMarkup()), Change.ofRemoval(path));

        return HttpResponse.from(future.thenApply(unused -> HttpResponse.of(HttpStatus.OK)));
    }

    /**
     * GET /projects/{projectName}/repositories/{repoName}/history{path}?from=x.x&amp;to=x.x
     * Returns a history between the specified revisions.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repositories/(?<repoName>[^/]+)" +
         "/history(?<path>/.*$)")
    public CompletionStage<List<CommitDto>> getHistory(@Param String projectName,
                                                       @Param String repoName,
                                                       @Param String path,
                                                       @Param @Default("-1") String from,
                                                       @Param @Default("1") String to) {
        return projectManager().get(projectName).repos().get(repoName)
                               .history(new Revision(from),
                                        new Revision(to),
                                        path + "**")
                               .thenApply(commits -> commits.stream()
                                                            .map(DtoConverter::convert)
                                                            .collect(toList()));
    }

    /**
     * GET /projects/{projectName}/repositories/{repoName}/search/revisions/{revision}?term={term}
     * Finds the files matched by {@code term}.
     */
    @Get("/projects/{projectName}/repositories/{repoName}/search/revisions/{revision}")
    public CompletionStage<List<EntryDto>> search(@Param String projectName,
                                                  @Param String repoName,
                                                  @Param String revision,
                                                  @Param String term) {
        return projectManager().get(projectName).repos().get(repoName)
                               .find(new Revision(revision), normalizeSearchTerm(term), FIND_ALL_WITH_CONTENT)
                               .thenApply(entries -> entries.values().stream()
                                                            .map(DtoConverter::convert)
                                                            .collect(toList()));
    }

    /**
     * GET /projects/{projectName}/repositories/{repoName}/diff{path}?from={from}&amp;to={to}
     * Returns a diff of the specified path between the specified revisions.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repositories/(?<repoName>[^/]+)" +
         "/diff(?<path>/.*$)")
    public CompletionStage<List<ChangeDto>> getDiff(@Param String projectName,
                                                    @Param String repoName,
                                                    @Param String path,
                                                    @Param String from,
                                                    @Param String to) {
        return projectManager().get(projectName).repos().get(repoName)
                               .diff(new Revision(from), new Revision(to), path)
                               .thenApply(changeMap -> changeMap.values().stream()
                                                                .map(DtoConverter::convert)
                                                                .collect(toList()));
    }

    private CompletableFuture<?> push(String projectName, String repoName,
                                      Revision revision, Author author,
                                      String commitSummary, String commitDetail, Markup commitMarkup,
                                      Change<?> change) {
        final Repository repo = projectManager().get(projectName).repos().get(repoName);
        return push0(projectName, repoName, repo.normalizeNow(revision), author,
                     commitSummary, commitDetail, commitMarkup, change);
    }

    private CompletableFuture<?> push0(String projectName, String repoName,
                                       Revision normalizedRev, Author author,
                                       String commitSummary, String commitDetail, Markup commitMarkup,
                                       Change<?> change) {
        final CompletableFuture<Map<String, Change<?>>> f = normalizeChanges(
                projectManager(), projectName, repoName, normalizedRev, ImmutableList.of(change));

        return f.thenCompose(
                changes -> execute(Command.push(author, projectName, repoName, normalizedRev,
                                                commitSummary, commitDetail, commitMarkup, changes.values())));
    }

    private static Entry<CommitMessageDto, Change<?>> commitMessageAndChange(AggregatedHttpRequest request) {
        try {
            final JsonNode node = Jackson.readTree(request.contentUtf8());
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
            throw new IllegalArgumentException("invalid data to be parsed", e);
        }
    }

    private static CompletableFuture<Map<String, Change<?>>> normalizeChanges(
            ProjectManager projectManager, String projectName, String repoName, Revision baseRevision,
            Iterable<Change<?>> changes) {
        return projectManager.get(projectName).repos().get(repoName)
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

            if (term0.matches(".*[/*]+.*")) {
                sb.append(term0);
            } else {
                sb.append('*').append(term0).append('*');
            }
        }
        return sb.toString();
    }
}
