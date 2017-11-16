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

package com.linecorp.centraldogma.server.internal.command;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;

public final class SaveNamedQueryCommand extends ProjectCommand<Void> {

    private final String queryName;
    private final boolean enabled;
    private final String repositoryName;
    private final Query<?> query;
    private final String comment;
    private final Markup markup;

    @JsonCreator
    SaveNamedQueryCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                          @JsonProperty("author") @Nullable Author author,
                          @JsonProperty("projectName") String projectName,
                          @JsonProperty("queryName") String queryName,
                          @JsonProperty("enabled") boolean enabled,
                          @JsonProperty("repositoryName") String repositoryName,
                          @JsonProperty("query") Query<?> query,
                          @JsonProperty("comment") String comment,
                          @JsonProperty("markup") Markup markup) {

        super(CommandType.SAVE_NAMED_QUERY, timestamp, author, projectName);

        this.queryName = requireNonNull(queryName, "queryName");
        this.enabled = enabled;
        this.repositoryName = requireNonNull(repositoryName, "repositoryName");
        this.query = requireNonNull(query, "query");
        this.comment = requireNonNull(comment, "comment");
        this.markup = requireNonNull(markup, "markup");
    }

    @JsonProperty
    public String queryName() {
        return queryName;
    }

    @JsonProperty
    public boolean isEnabled() {
        return enabled;
    }

    @JsonProperty
    public String repositoryName() {
        return repositoryName;
    }

    @JsonProperty
    @SuppressWarnings("unchecked")
    public <T> Query<T> query() {
        return (Query<T>) query;
    }

    @JsonProperty
    public String comment() {
        return comment;
    }

    @JsonProperty
    public Markup markup() {
        return markup;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof SaveNamedQueryCommand)) {
            return false;
        }

        final SaveNamedQueryCommand that = (SaveNamedQueryCommand) obj;
        return super.equals(obj) &&
               queryName.equals(that.queryName) &&
               enabled == that.enabled &&
               repositoryName.equals(that.repositoryName) &&
               query.equals(that.query) &&
               comment.equals(that.comment) &&
               markup == that.markup;
    }

    @Override
    public int hashCode() {
        return Objects.hash(queryName, enabled, repositoryName, query, comment, markup) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("queryName", queryName)
                    .add("enabled", enabled)
                    .add("repositoryName", repositoryName)
                    .add("query", query)
                    .add("comment", comment)
                    .add("markup", markup);
    }
}
