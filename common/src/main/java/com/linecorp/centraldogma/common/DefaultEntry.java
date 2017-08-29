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

class DefaultEntry<T> implements Entry<T> {

    static final Entry<Void> ROOT_DIR = new DefaultEntry<>("/", null, EntryType.DIRECTORY);

    private final String path;
    private final T content;
    private final EntryType type;

    /**
     * Creates a new instance.
     *
     * @param path path of the given entry
     * @param content an object of given type {@code T}
     * @param type the type of given {@code content}
     */
    DefaultEntry(String path, T content, EntryType type) {
        this.path = requireNonNull(path, "path");
        this.type = requireNonNull(type, "type");

        final Class<?> entryContentType = type.type();
        if (entryContentType == Void.class) {
            this.content = null;
        } else {
            @SuppressWarnings("unchecked")
            T castContent = (T) entryContentType.cast(requireNonNull(content, "content"));
            this.content = castContent;
        }
    }

    @Override
    public EntryType type() {
        return type;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public T content() {
        return content;
    }

    @Override
    public String contentAsText() {
        if (content == null) {
            return null;
        }
        return content.toString();
    }

    @Override
    public int hashCode() {
        return type.hashCode() * 31 + path.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultEntry)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        DefaultEntry<T> that = (DefaultEntry<T>) o;

        return type() == that.type() && path().equals(that.path()) && Objects.equals(content(), that.content());
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();

        buf.append(Util.simpleTypeName(this));
        buf.append("[path=");
        buf.append(path);
        buf.append(", type=");
        buf.append(type);
        buf.append(", content=");
        buf.append(contentAsText());
        buf.append(']');

        return buf.toString();
    }
}
