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

import com.linecorp.centraldogma.internal.thrift.NamedQuery;

public class NamedQueryDto {
    private String name;
    private boolean enabled;
    private String repositoryName;
    private QueryDto query;
    private CommentDto comment;

    public NamedQueryDto() {}

    public NamedQueryDto(NamedQuery namedQuery) {
        name = namedQuery.getName();
        enabled = namedQuery.isEnabled();
        repositoryName = namedQuery.getRepositoryName();
        query = new QueryDto(namedQuery.getQuery());
        if (namedQuery.getComment() != null) {
            comment = new CommentDto(namedQuery.getComment());
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
    }

    public QueryDto getQuery() {
        return query;
    }

    public void setQuery(QueryDto query) {
        this.query = query;
    }

    public CommentDto getComment() {
        return comment;
    }

    public void setComment(CommentDto comment) {
        this.comment = comment;
    }
}
