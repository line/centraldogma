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

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;

@JsonInclude(Include.NON_EMPTY)
public class CommitDto {

    private final Revision revision;

    private final Author author;

    private final CommitMessageDto commitMessage;

    private final String pushedAt;

    @Nullable
    private final String commitId;

    @Nullable
    private final String upstreamCommitId;

    public CommitDto(Revision revision, Author author, CommitMessageDto commitMessage,
                     long commitTimeMillis) {
        this(revision, author, commitMessage, commitTimeMillis, null, null);
    }

    public CommitDto(Revision revision, Author author, CommitMessageDto commitMessage, long commitTimeMillis,
                     @Nullable String commitId, @Nullable String upstreamCommitId) {
        this.revision = requireNonNull(revision, "revision");
        this.author = requireNonNull(author, "author");
        this.commitMessage = requireNonNull(commitMessage, "commitMessage");
        pushedAt = ISO_INSTANT.format(Instant.ofEpochMilli(commitTimeMillis));
        this.commitId = commitId;
        this.upstreamCommitId = upstreamCommitId;
    }

    @JsonProperty("revision")
    public Revision revision() {
        return revision;
    }

    @JsonProperty("author")
    public Author author() {
        return author;
    }

    @JsonProperty("commitMessage")
    public CommitMessageDto commitMessage() {
        return commitMessage;
    }

    @JsonProperty("pushedAt")
    public String pushedAt() {
        return pushedAt;
    }

    /**
     * Returns the SHA-1 of the Git commit that stores this commit.
     */
    @Nullable
    @JsonProperty("commitId")
    public String commitId() {
        return commitId;
    }

    /**
     * Returns the SHA-1 of the upstream Git commit this commit was mirrored from.
     */
    @Nullable
    @JsonProperty("upstreamCommitId")
    public String upstreamCommitId() {
        return upstreamCommitId;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("revision", revision())
                          .add("author", author())
                          .add("commitMessage", commitMessage())
                          .add("pushedAt", pushedAt())
                          .add("commitId", commitId())
                          .add("upstreamCommitId", upstreamCommitId())
                          .toString();
    }
}
