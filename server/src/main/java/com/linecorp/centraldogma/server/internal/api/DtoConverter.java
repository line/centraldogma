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

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.ChangeDto;
import com.linecorp.centraldogma.internal.api.v1.CommitDto;
import com.linecorp.centraldogma.internal.api.v1.CommitMessageDto;
import com.linecorp.centraldogma.internal.api.v1.EntryDto;
import com.linecorp.centraldogma.internal.api.v1.ProjectDto;
import com.linecorp.centraldogma.internal.api.v1.RepositoryDto;
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

    public static <T> EntryDto<T> convert(Repository repository, Entry<T> entry) {
        requireNonNull(entry, "entry");
        return convert(repository, entry.path(), entry.type(), entry.content());
    }

    public static <T> EntryDto<T> convert(Repository repository, QueryResult<T> result, String path) {
        requireNonNull(result, "result");
        return convert(repository, path, result.type(), result.content());
    }

    public static <T> EntryDto<T> convert(Repository repository, String path, EntryType type) {
        return convert(repository, path, type, null);
    }

    public static <T> EntryDto<T> convert(Repository repository, String path, EntryType type,
                                          @Nullable T content) {
        requireNonNull(repository, "repository");
        return new EntryDto<T>(requireNonNull(path, "path"), requireNonNull(type, "type"),
                               repository.parent().name(), repository.name(), content);
    }

    public static CommitDto convert(Commit commit) {
        return convert(commit, ImmutableList.of());
    }

    public static CommitDto convert(Commit commit, Iterable<EntryDto<?>> entries) {
        requireNonNull(commit, "commit");
        requireNonNull(entries, "entries");

        return convert(commit.revision(), commit.author(),
                       new CommitMessageDto(commit.summary(), commit.detail(), commit.markup()),
                       commit.when(), entries);
    }

    public static CommitDto convert(Revision revision, Author author, CommitMessageDto commitMessage,
                                    long commitTimeMillis, Iterable<EntryDto<?>> entries) {
        return new CommitDto(revision, author, commitMessage, commitTimeMillis, ImmutableList.copyOf(entries));
    }

    public static <T> ChangeDto<T> convert(Change<T> change) {
        requireNonNull(change, "change");
        return new ChangeDto<>(change.path(), change.type(), change.content());
    }

    private DtoConverter() {}
}
