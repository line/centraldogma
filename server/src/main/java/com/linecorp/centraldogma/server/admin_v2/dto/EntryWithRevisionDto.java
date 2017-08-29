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

import com.google.common.base.MoreObjects;

public class EntryWithRevisionDto {
    private EntryDto file;
    private String revision;

    public EntryWithRevisionDto(final EntryDto file, final String revision) {
        this.file = file;
        this.revision = revision;
    }

    public EntryDto getFile() {
        return file;
    }

    public void setFile(EntryDto file) {
        this.file = file;
    }

    public String getRevision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("file", file)
                          .add("revision", revision)
                          .toString();
    }
}
