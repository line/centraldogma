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

package com.linecorp.centraldogma.server.internal.admin.dto;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.internal.thrift.Comment;

public class CommentDto {

    private String content;
    private String markup;

    public CommentDto() {}

    public CommentDto(Comment comment) {
        requireNonNull(comment, "comment");

        content = comment.getContent();
        markup = comment.getMarkup().name();
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMarkup() {
        return markup;
    }

    public void setMarkup(String markup) {
        this.markup = markup;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("content", content)
                          .add("markup", markup)
                          .toString();
    }
}
