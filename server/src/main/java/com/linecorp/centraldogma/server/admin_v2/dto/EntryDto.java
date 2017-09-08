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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.internal.thrift.Entry;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EntryDto {

    private String path;
    private String type;
    private String content;

    public EntryDto() {}

    public EntryDto(Entry file) {
        requireNonNull(file, "file");

        path = file.getPath();
        type = file.getType().name();
        content = file.getContent();
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getName() {
        if (path == null) {
            return null;
        } else {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("path", path)
                          .add("type", type)
                          .add("content", content)
                          .toString();
    }
}
