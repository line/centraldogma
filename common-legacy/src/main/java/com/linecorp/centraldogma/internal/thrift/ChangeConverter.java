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

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Converter;

import com.linecorp.centraldogma.common.ChangeFormatException;
import com.linecorp.centraldogma.internal.Jackson;

/**
 * Provides a function converting back and forth between {@link Change} and
 * {@link com.linecorp.centraldogma.common.Change}.
 */
public final class ChangeConverter extends Converter<com.linecorp.centraldogma.common.Change<?>, Change> {
    public static final Converter<com.linecorp.centraldogma.common.Change<?>, Change> TO_DATA =
            new ChangeConverter();

    public static final Converter<Change, com.linecorp.centraldogma.common.Change<?>> TO_MODEL =
            TO_DATA.reverse();

    private ChangeConverter() {}

    @Override
    protected Change doForward(com.linecorp.centraldogma.common.Change<?> value) {
        final Change change = new Change(value.path(), convertChangeType(value.type()));
        switch (change.getType()) {
            case UPSERT_JSON:
            case APPLY_JSON_PATCH:
                try {
                    change.setContent(Jackson.writeValueAsString(value.content()));
                } catch (JsonProcessingException e) {
                    throw new ChangeFormatException("failed to read a JSON tree", e);
                }
                break;
            case UPSERT_TEXT:
            case APPLY_TEXT_PATCH:
            case RENAME:
                change.setContent((String) value.content());
                break;
            case REMOVE:
                break;
        }
        return change;
    }

    @Override
    protected com.linecorp.centraldogma.common.Change<?> doBackward(Change c) {
        switch (c.getType()) {
            case UPSERT_JSON:
                return com.linecorp.centraldogma.common.Change.ofJsonUpsert(c.getPath(),
                                                                            c.getContent());
            case UPSERT_TEXT:
                return com.linecorp.centraldogma.common.Change.ofTextUpsert(c.getPath(),
                                                                            c.getContent());
            case REMOVE:
                return com.linecorp.centraldogma.common.Change.ofRemoval(c.getPath());
            case RENAME:
                return com.linecorp.centraldogma.common.Change.ofRename(c.getPath(), c.getContent());
            case APPLY_JSON_PATCH:
                return com.linecorp.centraldogma.common.Change.ofJsonPatch(c.getPath(), c.getContent());
            case APPLY_TEXT_PATCH:
                return com.linecorp.centraldogma.common.Change.ofTextPatch(c.getPath(),
                                                                           c.getContent());
        }

        throw new Error();
    }

    @Nullable
    private static ChangeType convertChangeType(com.linecorp.centraldogma.common.ChangeType type) {
        if (type == null) {
            return null;
        }

        return ChangeType.valueOf(type.name());
    }
}
