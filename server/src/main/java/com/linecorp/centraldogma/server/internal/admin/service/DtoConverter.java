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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.admin.dto.AuthorDto;
import com.linecorp.centraldogma.server.internal.admin.dto.ChangeDto;
import com.linecorp.centraldogma.server.internal.admin.dto.CommentDto;
import com.linecorp.centraldogma.server.internal.admin.dto.CommitDto;
import com.linecorp.centraldogma.server.internal.admin.dto.EntryDto;
import com.linecorp.centraldogma.server.internal.admin.dto.EntryWithRevisionDto;
import com.linecorp.centraldogma.server.internal.admin.dto.ProjectDto;
import com.linecorp.centraldogma.server.internal.admin.dto.RepositoryDto;
import com.linecorp.centraldogma.server.internal.admin.dto.RevisionDto;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * A utility class to convert domain objects to DTO objects.
 */
final class DtoConverter {

    static CompletableFuture<RepositoryDto> convert(Repository repository) {
        return repository.history(Revision.HEAD, Revision.HEAD, Repository.ALL_PATH, 1)
                         .thenApply(history -> fromCommit(repository.name(), history));
    }

    static ProjectDto convert(Project project) {
        ProjectDto dto = new ProjectDto();
        dto.setName(project.name());
        return dto;
    }

    static CommitDto convert(Commit commit) {
        CommitDto dto = new CommitDto();
        dto.setAuthor(convert(commit.author()));
        dto.setRevision(convert(commit.revision()));
        dto.setTimestamp(commit.whenAsText());
        dto.setSummary(commit.summary());
        dto.setDetail(convert(commit.detail(), commit.markup()));
        return dto;
    }

    static RevisionDto convert(Revision revision) {
        return new RevisionDto(revision.major(), 0, revision.text());
    }

    static AuthorDto convert(Author author) {
        AuthorDto dto = new AuthorDto();
        dto.setName(author.name());
        dto.setEmail(author.email());
        return dto;
    }

    static CommentDto convert(String content, Markup markup) {
        CommentDto dto = new CommentDto();
        dto.setContent(content);
        dto.setMarkup(markup.name());
        return dto;
    }

    static ChangeDto convert(Change<?> change) {
        ChangeDto dto = new ChangeDto();
        dto.setPath(change.path());
        dto.setType(change.type().name());
        dto.setContent(change.contentAsText());
        return dto;
    }

    static EntryDto convert(Entry<?> entry) {
        EntryDto dto = new EntryDto();
        dto.setPath(entry.path());
        dto.setType(entry.type().name());
        dto.setContent(entry.contentAsText());
        return dto;
    }

    static EntryWithRevisionDto convert(String path, QueryResult<?> queryResult) {
        EntryDto entryDto = new EntryDto();
        entryDto.setPath(path);
        entryDto.setType(queryResult.type().name());
        entryDto.setContent(queryResult.contentAsText());

        return new EntryWithRevisionDto(entryDto, queryResult.revision().text());
    }

    static RepositoryDto fromCommit(String name, List<Commit> history) {
        RepositoryDto dto = new RepositoryDto();
        dto.setName(name);
        dto.setHead(convert(history.get(0)));
        return dto;
    }

    private DtoConverter() {}
}

