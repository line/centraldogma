/*
 * Copyright 2024 LINE Corporation
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

import java.util.function.Function;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.EntryType;

/**
 * A {@link Function} which is used for transforming the content at the specified path of the repository.
 */
public final class ContentTransformer<T> {

    private final String path;
    private final EntryType entryType;
    private final Function<T, T> transformer;

    /**
     * Creates a new instance.
     */
    public ContentTransformer(String path, EntryType entryType, Function<T, T> transformer) {
        this.path = requireNonNull(path, "path");
        this.entryType = requireNonNull(entryType, "entryType");
        this.transformer = requireNonNull(transformer, "transformer");
    }

    /**
     * Returns the path of the content to be transformed.
     */
    public String path() {
        return path;
    }

    /**
     * Returns the {@link EntryType} of the content to be transformed.
     */
    public EntryType entryType() {
        return entryType;
    }

    /**
     * Returns the {@link Function} which transforms the content.
     */
    public Function<T, T> transformer() {
        return transformer;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("path", path)
                          .add("entryType", entryType)
                          .add("transformer", transformer)
                          .toString();
    }
}
