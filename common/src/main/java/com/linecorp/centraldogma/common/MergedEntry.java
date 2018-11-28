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

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

/**
 * A merged entry in a repository.
 *
 * @param <T> the content type. It is {@link JsonNode} because only JSON merge is currently supported.
 */
public final class MergedEntry<T> implements ContentHolder<T> {

    /**
     * Returns a newly-created {@link MergedEntry}.
     *
     * @param revision the revision of the {@link MergedEntry}
     * @param type the type of the {@link MergedEntry}
     * @param content the content of the {@link MergedEntry}
     * @param <T> the content type. It is {@link JsonNode} because only JSON merge is currently supported.
     * @param paths the paths which participated to compose the {@link MergedEntry}
     */
    public static <T> MergedEntry<T> of(Revision revision, EntryType type, T content, String... paths) {
        return new MergedEntry<>(revision, type, content, ImmutableList.copyOf(requireNonNull(paths, "paths")));
    }

    /**
     * Returns a newly-created {@link MergedEntry}.
     *
     * @param revision the revision of the {@link MergedEntry}
     * @param type the type of the {@link MergedEntry}
     * @param content the content of the {@link MergedEntry}
     * @param <T> the content type. It is {@link JsonNode} because only JSON merge is currently supported.
     * @param paths the paths which participated to compose the {@link MergedEntry}
     */
    public static <T> MergedEntry<T> of(Revision revision, EntryType type, T content, Iterable<String> paths) {
        return new MergedEntry<>(revision, type, content, paths);
    }

    private final Revision revision;
    private final EntryType type;
    private final List<String> paths;
    private final T content;
    @Nullable
    private String contentAsText;
    @Nullable
    private String contentAsPrettyText;

    /**
     * Creates a new instance.
     */
    private MergedEntry(Revision revision, EntryType type, T content, Iterable<String> paths) {
        this.revision = requireNonNull(revision, "revision");
        this.type = requireNonNull(type, "type");
        requireNonNull(content, "content");
        final Class<?> entryType = type.type();
        checkArgument(entryType.isAssignableFrom(content.getClass()),
                      "content type: %s (expected: %s)", content.getClass(), entryType);
        this.content = content;
        this.paths = ImmutableList.copyOf(requireNonNull(paths, "paths"));
    }

    // TODO(minwoox) Move this method upto ContentHolder when we include the revision in Entry as well.
    /**
     * Returns the {@link Revision} of this {@link MergedEntry}.
     */
    public Revision revision() {
        return revision;
    }

    @Override
    public EntryType type() {
        return type;
    }

    @Override
    public T content() {
        return content;
    }

    /**
     * Returns the paths which participated to compose the {@link MergedEntry}.
     */
    public List<String> paths() {
        return paths;
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
        return (revision.hashCode() * 31 + type.hashCode()) * 31 + content.hashCode();
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
        return revision.equals(that.revision) &&
               type == that.type &&
               content.equals(that.content) &&
               paths.equals(that.paths);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("revision", revision())
                          .add("type", type())
                          .add("content", contentAsText())
                          .add("paths", paths())
                          .toString();
    }
}
