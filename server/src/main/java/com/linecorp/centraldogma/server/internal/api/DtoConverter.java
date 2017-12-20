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

package com.linecorp.centraldogma.server.internal.api;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.CONTENTS;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.Iterables;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.ChangeDto;
import com.linecorp.centraldogma.internal.api.v1.CommitDto;
import com.linecorp.centraldogma.internal.api.v1.EntryDto;
import com.linecorp.centraldogma.internal.api.v1.ProjectDto;
import com.linecorp.centraldogma.internal.api.v1.RepositoryDto;
import com.linecorp.centraldogma.internal.api.v1.WatchResultDto;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * A utility class to convert domain objects to DTO objects.
 */
final class DtoConverter {

    public static ProjectDto convert(Project project) {
        requireNonNull(project, "project");
        return new ProjectDto(project.name(), project.author(), project.creationTimeMillis());
    }

    public static RepositoryDto convert(Repository repository) {
        requireNonNull(repository, "repository");
        final Revision headRevision = repository.normalizeNow(Revision.HEAD);
        final String projectName = repository.parent().name();
        final String repoName = repository.name();

        return new RepositoryDto(projectName, repoName, repository.author(), headRevision,
                                 repository.creationTimeMillis());
    }

    public static <T> EntryDto<T> convert(Entry<T> entry) {
        return new EntryDto<T>(entry);
    }

    public static <T> EntryDto<T> convert(QueryResult<T> result, String path) {
        requireNonNull(result, "result");
        return new EntryDto<>(path, result.type(), result.content());
    }

    public static EntryDto<?> convert(Revision revision, String projectName, String repoName, String path,
                                   EntryType type, long commitTimeMillis) {
        return new EntryDto(path, type, projectName, repoName, revision, commitTimeMillis);
    }

    public static CommitDto convert(Commit commit) {
        return new CommitDto(commit);
    }

    public static <T> ChangeDto<T> convert(Change<T> change) {
        requireNonNull(change, "change");
        return new ChangeDto<>(change.path(), change.type(), change.content());
    }

    public static WatchResultDto convert(Commit commit, Iterable<Entry<?>> entries,
                                         String projectName, String repoName) {
        requireNonNull(entries, "entries");
        requireNonNull(projectName, "projectName");
        requireNonNull(repoName, "repoName");

        checkArgument(Iterables.size(entries) >= 1, "should have at least one entry.");

        final String contentsUrl = contentsUrl(projectName, repoName) + Iterables.get(entries, 0).path();
        return new WatchResultDto(convert(commit), contentsUrl);
    }

    // TODO(minwoox) replace with URI template processor implementing RFC6570
    private static String contentsUrl(String projectName, String repoName) {
        return PROJECTS_PREFIX + '/' + projectName + REPOS + '/' + repoName + CONTENTS;
    }

    private DtoConverter() {}
}
