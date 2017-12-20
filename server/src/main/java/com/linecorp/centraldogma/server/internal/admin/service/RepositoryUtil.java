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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.api.AbstractService;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

/**
 * A utility class which provides common functions for a repository.
 */
final class RepositoryUtil {

    static CompletableFuture<?> push(AbstractService service,
                                     String projectName, String repositoryName,
                                     Revision revision, Author author,
                                     String commitSummary, String commitDetail, Markup commitMarkup,
                                     Change<?> change) {
        return service
                .projectManager()
                .get(projectName).repos().get(repositoryName)
                .normalize(revision)
                .thenCompose(normalizedRevision ->
                                     push0(service, projectName, repositoryName, revision, author,
                                           commitSummary, commitDetail, commitMarkup, change));
    }

    private static CompletableFuture<?> push0(AbstractService service,
                                              String projectName, String repositoryName,
                                              Revision normalizedRev, Author author,
                                              String commitSummary, String commitDetail, Markup commitMarkup,
                                              Change<?> change) {
        final CompletableFuture<Map<String, Change<?>>> f = normalizeChanges(
                service.projectManager(), projectName, repositoryName, normalizedRev, ImmutableList.of(change));

        return f.thenCompose(
                changes -> service.execute(
                        Command.push(author, projectName, repositoryName, normalizedRev,
                                     commitSummary, commitDetail, commitMarkup, changes.values())));
    }

    private static CompletableFuture<Map<String, Change<?>>> normalizeChanges(
            ProjectManager projectManager, String projectName, String repositoryName, Revision baseRevision,
            Iterable<Change<?>> changes) {
        return projectManager
                .get(projectName).repos().get(repositoryName)
                .previewDiff(baseRevision, changes);
    }

    private RepositoryUtil() {}
}
