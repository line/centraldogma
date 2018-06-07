/*
 * Copyright 2018 LINE Corporation
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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;

@JsonInclude(Include.NON_EMPTY)
public class WatchResultDto extends CommitDto {

    private final EntryDto<?> entry;

    public WatchResultDto(Revision revision, Author author, CommitMessageDto commitMessage,
                          long commitTimeMillis, @Nullable EntryDto<?> entry) {
        super(revision, author, commitMessage, commitTimeMillis);
        this.entry = entry;
    }

    @JsonProperty("entry")
    public EntryDto<?> entry() {
        return entry;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("revision", revision())
                          .add("author", author())
                          .add("commitMessage", commitMessage())
                          .add("pushedAt", pushedAt())
                          .add("entry", entry())
                          .toString();
    }
}
