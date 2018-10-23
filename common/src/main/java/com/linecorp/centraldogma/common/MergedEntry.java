/*
 * Copyright 2018 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;

/**
 * A merged entry in a repository.
 *
 * @param <T> the content type. It is {@link JsonNode} because only JSON merger is currently supported.
 */
public final class MergedEntry<T> implements ContentHolder<T> {

    /**
     * Returns a newly-created {@link MergedEntry}.
     *
     * @param type the type of the {@link Entry}
     * @param content the content of the {@link Entry}
     * @param <T> the content type. It is {@link JsonNode} because only JSON aggregation is currently supported.
     */
    public static <T> MergedEntry<T> of(EntryType type, T content) {
        return new MergedEntry<>(type, content);
    }

    private final EntryType type;
    private final T content;
    @Nullable
    private String contentAsText;
    @Nullable
    private String contentAsPrettyText;

    /**
     * Creates a new instance.
     */
    private MergedEntry(EntryType type, T content) {
        this.type = requireNonNull(type, "type");
        requireNonNull(content, "content");
        final Class<?> entryType = type.type();
        checkArgument(entryType.isAssignableFrom(content.getClass()),
                      "content type: %s (expected: %s)", content.getClass(), entryType);
        this.content = content;
    }

    @Override
    public EntryType type() {
        return type;
    }

    @Override
    public T content() {
        return content;
    }

    @Override
    public String contentAsText() {
        if (contentAsText == null) {
            contentAsText = ContentHolder.super.contentAsText();
        }
        return contentAsText;
    }

    @Override
    public String contentAsPrettyText() {
        if (contentAsPrettyText == null) {
            contentAsPrettyText = ContentHolder.super.contentAsPrettyText();
        }
        return contentAsPrettyText;
    }

    @Override
    public int hashCode() {
        return type.hashCode() * 31 + content.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MergedEntry)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        final MergedEntry<T> that = (MergedEntry<T>) o;
        return type == that.type && content.equals(that.content);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("type", type)
                          .add("content", contentAsText())
                          .toString();
    }
}
