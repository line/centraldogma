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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.internal.Jackson;

/**
 * A file or a directory in a repository.
 *
 * @param <T> the content type. {@link JsonNode} if JSON. {@link String} if text.
 */
public final class Entry<T> implements ContentHolder<T> {

    /**
     * Returns a newly-created {@link Entry} of a directory.
     *
     * @param revision the revision of the directory
     * @param path the path of the directory
     */
    public static Entry<Void> ofDirectory(Revision revision, String path) {
        return new Entry<>(revision, path, EntryType.DIRECTORY, null);
    }

    /**
     * Returns a newly-created {@link Entry} of a JSON file.
     *
     * @param revision the revision of the JSON file
     * @param path the path of the JSON file
     * @param content the content of the JSON file
     */
    public static Entry<JsonNode> ofJson(Revision revision, String path, JsonNode content) {
        return new Entry<>(revision, path, EntryType.JSON, content);
    }

    /**
     * Returns a newly-created {@link Entry} of a JSON file.
     *
     * @param revision the revision of the JSON file
     * @param path the path of the JSON file
     * @param content the content of the JSON file
     *
     * @throws JsonParseException if the {@code content} is not a valid JSON
     */
    public static Entry<JsonNode> ofJson(Revision revision, String path, String content)
            throws JsonParseException {
        return ofJson(revision, path, Jackson.readTree(content));
    }

    /**
     * Returns a newly-created {@link Entry} of a text file.
     *
     * @param revision the revision of the text file
     * @param path the path of the text file
     * @param content the content of the text file
     */
    public static Entry<String> ofText(Revision revision, String path, String content) {
        return new Entry<>(revision, path, EntryType.TEXT, content);
    }

    /**
     * Returns a newly-created {@link Entry}.
     *
     * @param revision the revision of the {@link Entry}
     * @param path the path of the {@link Entry}
     * @param content the content of the {@link Entry}
     * @param type the type of the {@link Entry}
     * @param <T> the content type. {@link JsonNode} if JSON. {@link String} if text.
     */
    public static <T> Entry<T> of(Revision revision, String path, EntryType type, @Nullable T content) {
        return new Entry<>(revision, path, type, content);
    }

    private final Revision revision;
    private final String path;
    @Nullable
    private final T content;
    private final EntryType type;
    @Nullable
    private String contentAsText;
    @Nullable
    private String contentAsPrettyText;

    /**
     * Creates a new instance.
     *
     * @param revision the revision of the entry
     * @param path the path of the entry
     * @param type the type of given {@code content}
     * @param content an object of given type {@code T}
     */
    private Entry(Revision revision, String path, EntryType type, @Nullable T content) {
        requireNonNull(revision, "revision");
        checkArgument(!revision.isRelative(), "revision: %s (expected: absolute revision)", revision);
        this.revision = revision;
        this.path = requireNonNull(path, "path");
        this.type = requireNonNull(type, "type");

        final Class<?> entryContentType = type.type();

        if (entryContentType == Void.class) {
            checkArgument(content == null, "content: %s (expected: null)", content);
            this.content = null;
        } else {
            @SuppressWarnings("unchecked")
            final T castContent = (T) entryContentType.cast(requireNonNull(content, "content"));
            this.content = castContent;
        }
    }

    /**
     * Returns the revision of this {@link Entry}.
     */
    public Revision revision() {
        return revision;
    }

    /**
     * Returns the path of this {@link Entry}.
     */
    public String path() {
        return path;
    }

    /**
     * Returns if this {@link Entry} has content, which is always {@code true} if it's not a directory.
     */
    public boolean hasContent() {
        return content != null;
    }

    /**
     * If this {@link Entry} has content, invoke the specified {@link Consumer} with the content.
     */
    public void ifHasContent(Consumer<? super T> consumer) {
        requireNonNull(consumer, "consumer");
        if (content != null) {
            consumer.accept(content);
        }
    }

    @Override
    public EntryType type() {
        return type;
    }

    @Override
    public T content() {
        if (content == null) {
            throw new EntryNoContentException(type, revision, path);
        }
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
        return (revision.hashCode() * 31 + type.hashCode()) * 31 + path.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Entry)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        final Entry<T> that = (Entry<T>) o;

        return type == that.type && revision.equals(that.revision) && path.equals(that.path) &&
               Objects.equals(content, that.content);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("revision", revision.text())
                          .add("path", path)
                          .add("type", type)
                          .add("content", hasContent() ? contentAsText() : null)
                          .toString();
    }
}
