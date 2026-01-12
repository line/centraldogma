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
import static com.linecorp.centraldogma.internal.Json5.isJson5;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Json5;
import com.linecorp.centraldogma.internal.Yaml;

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
        if (!path.endsWith("/")) {
            path += "/";
        }
        return new Entry<>(revision, path, EntryType.DIRECTORY, null, null, null);
    }

    /**
     * Returns a newly-created {@link Entry} of a JSON file.
     *
     * @param revision the revision of the JSON file
     * @param path the path of the JSON file
     * @param content the content of the JSON file
     */
    public static Entry<JsonNode> ofJson(Revision revision, String path, JsonNode content) {
        return new Entry<>(revision, path, EntryType.JSON, content, null, null);
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
        final JsonNode jsonNode;
        if (isJson5(path)) {
            jsonNode = Json5.readTree(content);
        } else {
            jsonNode = Jackson.readTree(content);
        }
        return new Entry<>(revision, path, EntryType.JSON, jsonNode, content, null);
    }

    /**
     * Returns a newly-created {@link Entry} of a YAML file with the given content.
     *
     * @param revision the revision of the YAML file
     * @param path the path of the YAML file
     * @param content the content of the YAML file
     */
    public static Entry<JsonNode> ofYaml(Revision revision, String path, JsonNode content) {
        return new Entry<>(revision, path, EntryType.YAML, content, null, null);
    }

    /**
     * Returns a newly-created {@link Entry} of a YAML file.
     *
     * @param revision the revision of the YAML file
     * @param path the path of the YAML file
     * @param content the content of the YAML file
     *
     * @throws JsonParseException if the {@code content} is not a valid YAML
     */
    public static Entry<JsonNode> ofYaml(Revision revision, String path, String content)
            throws JsonParseException {
        final JsonNode jsonNode = Yaml.readTree(content);
        return new Entry<>(revision, path, EntryType.YAML, jsonNode, content, null);
    }

    /**
     * Returns a newly-created {@link Entry} of a text file.
     *
     * @param revision the revision of the text file
     * @param path the path of the text file
     * @param content the content of the text file
     */
    public static Entry<String> ofText(Revision revision, String path, String content) {
        return new Entry<>(revision, path, EntryType.TEXT, content, content, null);
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
        return of(revision, path, type, content, null);
    }

    /**
     * Returns a newly-created {@link Entry}.
     *
     * @param revision the revision of the {@link Entry}
     * @param path the path of the {@link Entry}
     * @param content the content of the {@link Entry}
     * @param type the type of the {@link Entry}
     * @param <T> the content type. {@link JsonNode} if JSON. {@link String} if text.
     * @param variableRevision the revision of the variables that were used to render the template and generate
     *                         this {@link Entry}
     */
    public static <T> Entry<T> of(Revision revision, String path, EntryType type, @Nullable T content,
                                  @Nullable Revision variableRevision) {
        return new Entry<>(revision, path, type, content, null, variableRevision);
    }

    private final Revision revision;
    @Nullable
    private final Revision variableRevision;
    private final String path;
    @Nullable
    private final T content;
    @Nullable
    private final String rawContent;
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
     * @param rawContent the raw content string, which is used for viewing the original JSON text
     */
    private Entry(Revision revision, String path, EntryType type, @Nullable T content,
                  @Nullable String rawContent, @Nullable Revision variableRevision) {
        requireNonNull(revision, "revision");
        checkArgument(!revision.isRelative(), "revision: %s (expected: absolute revision)", revision);
        this.revision = revision;
        this.path = requireNonNull(path, "path");
        this.type = requireNonNull(type, "type");

        final Class<?> entryContentType = type.type();

        if (entryContentType == Void.class) {
            checkArgument(content == null, "content: %s (expected: null)", content);
            this.content = null;
            this.rawContent = null;
        } else {
            @SuppressWarnings("unchecked")
            final T castContent = (T) entryContentType.cast(requireNonNull(content, "content"));
            this.content = castContent;
            this.rawContent = rawContent;
        }
        this.variableRevision = variableRevision;
    }

    /**
     * Returns the revision of this {@link Entry}.
     */
    public Revision revision() {
        return revision;
    }

    /**
     * Returns the revision of the variables that were used to render the template and generate this
     * {@link Entry}.
     *
     * <p>{@code null} if this {@link Entry} was not created by applying variables to a template.
     */
    @Nullable
    public Revision variableRevision() {
        return variableRevision;
    }

    /**
     * Sets the revision of the variables that were used to render the template and generate this {@link Entry}.
     *
     * <p>This value is set only when the {@link Entry} is created by applying variables to a template.
     */
    public Entry<T> withVariableRevision(Revision variableRevision) {
        requireNonNull(variableRevision, "variableRevision");
        return new Entry<>(revision, path, type, content, rawContent, variableRevision);
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

    /**
     * Returns the raw content if available.
     */
    @Nullable
    public String rawContent() {
        return rawContent;
    }

    @Override
    public String contentAsText() {
        if (rawContent != null) {
            return rawContent;
        }
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
    public JsonNode contentAsJson() throws JsonParseException {
        if (content instanceof JsonNode) {
            return (JsonNode) content;
        }

        if (rawContent != null) {
            return Jackson.readTree(path, rawContent);
        }

        return ContentHolder.super.contentAsJson();
    }

    @Override
    public int hashCode() {
        return Objects.hash(revision, path, content, type, variableRevision);
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

        return type == that.type &&
               revision.equals(that.revision) &&
               path.equals(that.path) &&
               Objects.equals(variableRevision, that.variableRevision) &&
               Objects.equals(content, that.content);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("revision", revision)
                          .add("variableRevision", variableRevision)
                          .add("path", path)
                          .add("type", type)
                          .add("content", hasContent() ? contentAsText() : null)
                          .toString();
    }
}
