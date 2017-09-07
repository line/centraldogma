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

import java.util.List;
import java.util.stream.Collectors;

import com.linecorp.centraldogma.internal.thrift.SchemaEntry;

public class SchemaEntryDto {
    private String repositoryName;
    private String path;
    private PropertyFilterDto propertyFilter;
    private List<String> types;
    private CommentDto comment;

    public SchemaEntryDto() {}

    public SchemaEntryDto(SchemaEntry schemaEntry) {
        repositoryName = schemaEntry.getRepositoryName();
        path = schemaEntry.getPath();
        propertyFilter = new PropertyFilterDto(schemaEntry.getPropertyFilter());
        types = schemaEntry.getTypes().stream().map(Enum::name).collect(Collectors.toList());
        if (schemaEntry.getComment() != null) {
            comment = new CommentDto(schemaEntry.getComment());
        }
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public PropertyFilterDto getPropertyFilter() {
        return propertyFilter;
    }

    public void setPropertyFilter(PropertyFilterDto propertyFilter) {
        this.propertyFilter = propertyFilter;
    }

    public List<String> getTypes() {
        return types;
    }

    public void setTypes(List<String> types) {
        this.types = types;
    }

    public CommentDto getComment() {
        return comment;
    }

    public void setComment(CommentDto comment) {
        this.comment = comment;
    }
}
