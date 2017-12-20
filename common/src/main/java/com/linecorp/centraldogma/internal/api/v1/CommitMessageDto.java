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

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Markup;

@JsonDeserialize(using = CommitMessageDtoDeserializer.class)
public class CommitMessageDto {

    private final String summary;

    private final String detail;

    private final Markup markup;

    public CommitMessageDto(String summary, @Nullable String detail, @Nullable Markup markup) {
        this.summary = requireNonNull(summary, "summary");
        this.detail = isNullOrEmpty(detail) ? "" : detail;
        this.markup = markup == null ? Markup.UNKNOWN : markup;
    }

    @JsonProperty("summary")
    public String summary() {
        return summary;
    }

    @JsonProperty("detail")
    public String detail() {
        return detail;
    }

    @JsonProperty("markup")
    public Markup markup() {
        return markup;
    }

    @Override
    public String toString() {
        final ToStringHelper stringHelper = MoreObjects.toStringHelper(this)
                                                       .add("summary", summary());
        if (!isNullOrEmpty(detail)) {
            stringHelper.add("detail", detail());
            stringHelper.add("markup", markup());
        }
        return stringHelper.toString();
    }
}
