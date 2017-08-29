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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Converter;

import com.linecorp.centraldogma.common.Jackson;

/**
 * Provides a function converting back and forth between {@link Entry} and
 * {@link com.linecorp.centraldogma.common.Entry}.
 */
public final class EntryConverter extends Converter<com.linecorp.centraldogma.common.Entry<?>, Entry> {
    public static final Converter<com.linecorp.centraldogma.common.Entry<?>, Entry> TO_DATA =
            new EntryConverter();

    public static final Converter<Entry, com.linecorp.centraldogma.common.Entry<?>> TO_MODEL =
            TO_DATA.reverse();

    private EntryConverter() {
    }

    @Override
    protected Entry doForward(com.linecorp.centraldogma.common.Entry<?> entry) {
        Entry file = new Entry(entry.path(), convertEntryType(entry.type()));
        switch (entry.type()) {
            case JSON:
                // FIXME(trustin): Inefficiency
                try {
                    file.setContent(Jackson.writeValueAsString(entry.content()));
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

    @Override
    protected com.linecorp.centraldogma.common.Entry<?> doBackward(Entry entry) {
        switch (entry.getType()) {
            case JSON:
                try {
                    JsonNode value = Jackson.readTree(entry.getContent());
                    return com.linecorp.centraldogma.common.Entry.ofJson(entry.getPath(), value);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            case TEXT:
                return com.linecorp.centraldogma.common.Entry.ofText(entry.getPath(), entry.getContent());
            case DIRECTORY:
                return com.linecorp.centraldogma.common.Entry.ofDirectory(entry.getPath());
            default:
                throw new IllegalArgumentException("unsupported entry type: " + entry.getType());
        }
    }

    /**
     * Converts {@link com.linecorp.centraldogma.common.EntryType} to {@link EntryType}.
     */
    public static EntryType convertEntryType(com.linecorp.centraldogma.common.EntryType type) {
        if (type == null) {
            return null;
        }

        switch (type) {
            case JSON:
                return EntryType.JSON;
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
}
