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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.linecorp.centraldogma.internal.thrift.Schema;

public class SchemaDto {
    private List<SchemaEntryDto> entries = new ArrayList<>();

    public SchemaDto() {
    }

    public SchemaDto(Schema schema) {
        entries = schema.getEntries().stream().map(SchemaEntryDto::new).collect(Collectors.toList());
    }

    public List<SchemaEntryDto> getEntries() {
        return entries;
    }

    public void setEntries(List<SchemaEntryDto> entries) {
        this.entries = entries;
    }
}
