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

package com.linecorp.centraldogma.server.admin_v2.dto;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.internal.thrift.Commit;

public class CommitDto {
    private RevisionDto revision;
    private AuthorDto author;
    private String timestamp;
    private String summary;
    private CommentDto detail;
    private List<ChangeDto> diffs = new ArrayList<>();

    public CommitDto() {}

    public CommitDto(Commit commit) {
        requireNonNull(commit, "commit");

        revision = new RevisionDto(commit.getRevision());
        author = new AuthorDto(commit.getAuthor());
        timestamp = commit.getTimestamp();
        summary = commit.getSummary();
        detail = new CommentDto(commit.getDetail());
        diffs = commit.getDiffs().stream().map(ChangeDto::new).collect(Collectors.toList());
    }

    public RevisionDto getRevision() {
        return revision;
    }

    public void setRevision(RevisionDto revision) {
        this.revision = revision;
    }

    public AuthorDto getAuthor() {
        return author;
    }

    public void setAuthor(AuthorDto author) {
        this.author = author;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public CommentDto getDetail() {
        return detail;
    }

    public void setDetail(CommentDto detail) {
        this.detail = detail;
    }

    public List<ChangeDto> getDiffs() {
        return diffs;
    }

    public void setDiffs(List<ChangeDto> diffs) {
        this.diffs = diffs;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("revision", revision)
                          .add("author", author)
                          .add("timestamp", timestamp)
                          .add("summary", summary)
                          .add("detail", detail)
                          .add("diffs", diffs)
                          .toString();
    }
}
