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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The type of a {@link Query}.
 */
public enum QueryType {
    /**
     * Retrieves the content in its native type. The return type depends on the {@link EntryType}:
     * <ul>
     *   <li>{@link EntryType#JSON} and {@link EntryType#YAML} entries return a {@code JsonNode}.</li>
     *   <li>{@link EntryType#TEXT} entries return a {@code String}.</li>
     * </ul>
     */
    IDENTITY(EnumSet.of(EntryType.TEXT, EntryType.JSON, EntryType.YAML)),

    /**
     * Retrieves the content as a {@code String}, regardless of the {@link EntryType}.
     * Unlike {@link #IDENTITY}, which returns a {@code JsonNode} for JSON and YAML entries,
     * this type always returns the textual representation of the content.
     */
    IDENTITY_TEXT(EnumSet.of(EntryType.TEXT, EntryType.JSON, EntryType.YAML)),

    /**
     * Retrieves the JSON content as a {@code JsonNode}. Only supports {@link EntryType#JSON} entries.
     */
    IDENTITY_JSON(EnumSet.of(EntryType.JSON)),

    /**
     * Retrieves the YAML content as a {@code JsonNode}. Only supports {@link EntryType#YAML} entries.
     */
    IDENTITY_YAML(EnumSet.of(EntryType.YAML)),

    /**
     * Applies a series of <a href="https://github.com/json-path/JsonPath/blob/master/README.md">JSON path
     * expressions</a> to the content.
     */
    JSON_PATH(EnumSet.of(EntryType.JSON, EntryType.YAML));

    private final Set<EntryType> supportedEntryTypes;

    QueryType(Set<EntryType> supportedEntryTypes) {
        this.supportedEntryTypes = Collections.unmodifiableSet(supportedEntryTypes);
    }

    /**
     * Returns the {@link Set} of {@link EntryType}s supported by this {@link QueryType}.
     */
    public Set<EntryType> supportedEntryTypes() {
        return supportedEntryTypes;
    }
}
