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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.ChangeType;

@JsonInclude(Include.NON_NULL)
public class ChangeDto<T> {

    private final String path;

    private final ChangeType type;

    private final T content;

    public ChangeDto(String path, ChangeType type, @Nullable T content) {
        this.path = requireNonNull(path, "path");
        this.type = requireNonNull(type, "type");
        this.content = content;
    }

    @JsonProperty("path")
    public String path() {
        return path;
    }

    @JsonProperty("type")
    public ChangeType type() {
        return type;
    }

    @Nullable
    @JsonProperty("content")
    public T content() {
        return content;
    }

    @Override
    public String toString() {
        final ToStringHelper stringHelper = MoreObjects.toStringHelper(this)
                                                       .add("path", path())
                                                       .add("type", type());
        if (content() != null) {
            stringHelper.add("content", content());
        }

        return stringHelper.toString();
    }
}
