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

package com.linecorp.centraldogma.internal.thrift;

import java.io.IOException;
import java.io.UncheckedIOException;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.jackson.Jackson;

/**
 * Provides a function converting back and forth between {@link Entry} and
 * {@link com.linecorp.centraldogma.common.Entry}.
 */
public final class EntryConverter {

    public static Entry convert(com.linecorp.centraldogma.common.Entry<?> entry) {
        final Entry file = new Entry(entry.path(), convertEntryType(entry.type()));
        switch (entry.type()) {
            case JSON:
            case YAML:
                // FIXME(trustin): Inefficiency
                try {
                    file.setContent(Jackson.of(entry.type()).writeValueAsString(entry.content()));
                } catch (JsonProcessingException e) {
                    throw new UncheckedIOException(e);
                }
                break;
            case TEXT:
                file.setContent((String) entry.content());
                break;
            case DIRECTORY:
                break;
            default:
                throw new IllegalArgumentException("unsupported entry type: " + entry.type());
        }
        return file;
    }

    public static com.linecorp.centraldogma.common.Entry<?> convert(
            com.linecorp.centraldogma.common.Revision revision, Entry entry) {
        switch (entry.getType()) {
            case JSON:
                try {
                    final JsonNode value = Jackson.ofJson().readTree(entry.getContent());
                    return com.linecorp.centraldogma.common.Entry.ofJson(revision, entry.getPath(), value);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            case TEXT:
                return com.linecorp.centraldogma.common.Entry.ofText(revision, entry.getPath(),
                                                                     entry.getContent());
            case DIRECTORY:
                return com.linecorp.centraldogma.common.Entry.ofDirectory(revision, entry.getPath());
            case YAML:
                try {
                    final JsonNode value = Jackson.ofYaml().readTree(entry.getContent());
                    return com.linecorp.centraldogma.common.Entry.ofYaml(revision, entry.getPath(), value);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            default:
                throw new IllegalArgumentException("unsupported entry type: " + entry.getType());
        }
    }

    /**
     * Converts {@link com.linecorp.centraldogma.common.EntryType} to {@link EntryType}.
     */
    @Nullable
    public static EntryType convertEntryType(com.linecorp.centraldogma.common.EntryType type) {
        if (type == null) {
            return null;
        }

        switch (type) {
            case JSON:
                return EntryType.JSON;
            case YAML:
                return EntryType.YAML;
            case TEXT:
                return EntryType.TEXT;
            case DIRECTORY:
                return EntryType.DIRECTORY;
        }

        throw new Error();
    }

    /**
     * Converts {@link EntryType} to {@link com.linecorp.centraldogma.common.EntryType}.
     */
    @Nullable
    public static com.linecorp.centraldogma.common.EntryType convertEntryType(EntryType type) {
        if (type == null) {
            return null;
        }

        switch (type) {
            case JSON:
                return com.linecorp.centraldogma.common.EntryType.JSON;
            case TEXT:
                return com.linecorp.centraldogma.common.EntryType.TEXT;
            case DIRECTORY:
                return com.linecorp.centraldogma.common.EntryType.DIRECTORY;
        }

        throw new Error();
    }

    private EntryConverter() {}
}
