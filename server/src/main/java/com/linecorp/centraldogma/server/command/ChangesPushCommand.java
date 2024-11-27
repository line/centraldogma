/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.centraldogma.server.command;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

/**
 * A {@link Command} which is used for pushing changes to the repository.
 */
public abstract class ChangesPushCommand<T> extends AbstractPushCommand<T> {

    private final List<Change<?>> changes;

    @JsonCreator
    ChangesPushCommand(CommandType type, @Nullable Long timestamp, @Nullable Author author,
                       String projectName, String repositoryName, Revision baseRevision,
                       String summary, String detail, Markup markup,
                       Iterable<Change<?>> changes) {
        super(type, timestamp, author, projectName, repositoryName, baseRevision, summary, detail, markup);
        this.changes = ImmutableList.copyOf(requireNonNull(changes, "changes"));
    }

    /**
     * Returns the {@link Change}s of the commit.
     */
    @JsonProperty
    public List<Change<?>> changes() {
        return changes;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof ChangesPushCommand)) {
            return false;
        }

        final ChangesPushCommand<?> that = (ChangesPushCommand<?>) obj;
        return super.equals(that) && changes.equals(that.changes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(changes) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper().add("changes", changes);
    }
}
