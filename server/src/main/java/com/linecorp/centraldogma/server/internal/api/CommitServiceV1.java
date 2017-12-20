/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.api;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.admin.decorator.ProjectMembersOnly;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * Annotated service object for fetching commits and diffs.
 */
@ExceptionHandler(HttpApiExceptionHandler.class)
public class CommitServiceV1 extends AbstractService {

    public CommitServiceV1(ProjectManager projectManager, CommandExecutor executor) {
        super(projectManager, executor);
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/commits/{revision}?path={path}&to={to}
     *
     * <p>Returns the list of commits in the path.
     */
    @Get("regex:/projects/(?<projectName>[^/]+)/repos/(?<repoName>[^/]+)/commits(?<revision>(|/.*))$")
    @Decorator(ProjectMembersOnly.class)
    public CompletionStage<?> listCommits(@Param("revision") String revision,
                                          @Param("path") @Default("/**") String path,
                                          @Param("to") Optional<String> to,
                                          @RequestObject Repository repository) {
        final Revision fromRevision;
        final Revision toRevision;

        // 1. only the "revision" is specified:       get the "revision"
        // 2. only the "to" is specified:             get from "HEAD" to "to"
        // 3. the "revision" and "to" is specified:   get from the "revision" to "to"
        // 4. nothing is specified:                   get from "HEAD" to "INIT"
        if (isNullOrEmpty(revision) || "/".equalsIgnoreCase(revision)) {
            fromRevision = Revision.HEAD;
            toRevision = to.map(Revision::new).orElse(Revision.INIT);
        } else {
            fromRevision = new Revision(revision.substring(1));
            toRevision = to.map(Revision::new).orElse(fromRevision);
        }

        return repository
                .history(fromRevision, toRevision, path)
                .thenApply(commits -> objectOrList(commits, DtoConverter::convert));
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/compare?path={path}&from={from}&to={to}&
     * queryType={queryType}&expression={expression}
     *
     * <p>Returns the diffs.
     */
    @Get("/projects/{projectName}/repos/{repoName}/compare")
    @Decorator(ProjectMembersOnly.class)
    public CompletionStage<?> getDiff(@Param("path") @Default("/**") String path,
                                      @Param("from") @Default("-1") String from,
                                      @Param("to") @Default("1") String to,
                                      @RequestObject Repository repository,
                                      @RequestObject(RequestQueryConverter.class) Optional<Query<?>> query) {
        if (query.isPresent()) {
            return repository.diff(new Revision(from), new Revision(to), query.get())
                             .thenApply(DtoConverter::convert);
        }
        return repository
                .diff(new Revision(from), new Revision(to), path)
                .thenApply(changeMap -> objectOrList(changeMap.values(), DtoConverter::convert));
    }

    private static <T> Object objectOrList(Collection<T> collection, Function<T, ?> converter) {
        if (collection.size() == 1) {
            return converter.apply(collection.iterator().next());
        }
        return collection.stream().map(converter).collect(toImmutableList());
    }
}
