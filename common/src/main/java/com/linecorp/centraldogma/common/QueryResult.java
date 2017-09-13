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

package com.linecorp.centraldogma.common;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;

/**
 * The result of a {@link Query} evaluation.
 */
public final class QueryResult<T> {

    private final Revision revision;
    private final EntryType type;
    private final T content;

    /**
     * Creates a new instance.
     *
     * @param revision the {@link Revision} of the repository where the {@link Query} was evaluated on
     * @param type the type of the {@link Entry} where the {@link Query} was evaluated on.
     *             {@code null} if the {@link Entry} does not exist.
     * @param content the result of the query evaluation
     */
    public QueryResult(Revision revision, @Nullable EntryType type, @Nullable T content) {
        this.revision = requireNonNull(revision, "revision");
        this.type = validateType(type);
        this.content = content;
    }

    private static EntryType validateType(EntryType type) {
        if (type == EntryType.DIRECTORY) {
            throw new IllegalArgumentException(EntryType.DIRECTORY + " not allowed as a watch result type");
        }
        return type;
    }

    /**
     * Returns the {@link Revision} of the repository where the {@link Query} was evaluated on.
     */
    public Revision revision() {
        return revision;
    }

    /**
     * Returns the type of the {@link Entry} where the {@link Query} was evaluated on.
     *
     * @return the type of the {@link Entry} if the {@link Entry} exists.
     *         {@code null} if the {@link Entry} does not exist.
     */
    @Nullable
    public EntryType type() {
        return type;
    }

    /**
     * Returns the result of the query evaluation.
     */
    @Nullable
    public T content() {
        return content;
    }

    /**
     * Returns the textual representation of {@link #content()}.
     *
     * @return the textual representation of {@link #content()} if {@link #content()} returned non-null.
     *         {@code null} if {@link #content()} returned {@code null}.
     */
    @Nullable
    public String contentAsText() {
        if (content instanceof CharSequence) {
            return content.toString();
        }

        if (content instanceof JsonNode) {
            try {
                return Jackson.writeValueAsString(content);
            } catch (JsonProcessingException e) {
                // Should never reach here.
                throw new Error(e);
            }
        }

        if (content == null) {
            return null;
        }

        // Should never reach here.
        throw new Error();
    }

    /**
     * Returns whether the {@link Entry} does not exist anymore.
     * This method is a shortcut of {@code type() == null}
     */
    public boolean isRemoved() {
        return type == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QueryResult)) {
            return false;
        }

        final QueryResult<?> that = (QueryResult<?>) o;
        return revision.equals(that.revision) &&
               type == that.type &&
               Objects.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return revision.hashCode() * 31 + type.hashCode();
    }

    @Override
    public String toString() {
        return "QueryResult(" + revision.text() + ", " + type + ", " + contentAsText() + ')';
    }
}
