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

package com.linecorp.centraldogma.internal.api.v1;

import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Revision;

public class CommitDto {

    private final Revision revision;

    private final Author author;

    private final CommitMessageDto commitMessage;

    private final String pushedAt;

    public CommitDto(Commit commit) {
        requireNonNull(commit, "commit");
        revision = commit.revision();
        author = commit.author();
        commitMessage = new CommitMessageDto(commit.summary(), commit.detail(), commit.markup());
        pushedAt = ISO_INSTANT.format(Instant.ofEpochMilli(commit.when()));
    }

    @JsonProperty("revision")
    public Revision revision() {
        return revision;
    }

    @JsonProperty("author")
    public Author author() {
        return author;
    }

    @JsonProperty("pushedAt")
    public String pushedAt() {
        return pushedAt;
    }

    @JsonProperty("commitMessage")
    public CommitMessageDto commitMessage() {
        return commitMessage;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("revision", revision())
                          .add("author", author())
                          .add("commitMessage", commitMessage())
                          .add("pushedAt", pushedAt())
                          .toString();
    }
}
