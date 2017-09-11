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

package com.linecorp.centraldogma.server.admin.dto;

import com.google.common.base.MoreObjects;

public class CommitMessageDto {

    private String summary;
    private CommentDto detail;

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

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("summary", summary)
                          .add("detail", detail)
                          .toString();
    }
}
