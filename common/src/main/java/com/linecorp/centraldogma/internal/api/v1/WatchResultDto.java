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

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

public class WatchResultDto {

    private final CommitDto head;

    private final String contentsUrl;

    public WatchResultDto(CommitDto head, String contentsUrl) {
        this.head = requireNonNull(head, "head");
        this.contentsUrl = requireNonNull(contentsUrl, "contentsUrl");
    }

    @JsonProperty("head")
    public CommitDto head() {
        return head;
    }

    @JsonProperty("contentsUrl")
    public String contentsUrl() {
        return contentsUrl;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("head", head())
                          .add("contentsUrl", contentsUrl())
                          .toString();
    }
}
