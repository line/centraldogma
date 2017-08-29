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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

public final class QueryResult<T> {

    private final Revision revision;
    private final EntryType type;
    private final T content;

    public QueryResult(Revision revision, EntryType type, T content) {
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

    public Revision revision() {
        return revision;
    }

    public EntryType type() {
        return type;
    }

    public T content() {
        return content;
    }

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
