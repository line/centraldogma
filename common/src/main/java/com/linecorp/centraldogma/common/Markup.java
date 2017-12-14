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

import static com.google.common.base.Strings.isNullOrEmpty;

import javax.annotation.Nullable;

import com.google.common.base.Ascii;

/**
 * The markup language of a {@link Commit} message.
 */
public enum Markup {
    /**
     * Unknown markup language.
     */
    UNKNOWN,
    /**
     * Plaintext.
     */
    PLAINTEXT,
    /**
     * Markdown.
     */
    MARKDOWN;

    private final String nameLowercased;

    Markup() {
        nameLowercased = Ascii.toLowerCase(name());
    }

    /**
     * Returns the lower-cased name.
     */
    public String nameLowercased() {
        return nameLowercased;
    }

    /**
     * Returns a {@link Markup} from the specified {@code value}. If none of markup is matched,
     * this will return {@link #UNKNOWN}.
     */
    public static Markup parse(@Nullable String value) {
        if (isNullOrEmpty(value)) {
            return UNKNOWN;
        }

        final String markup = Ascii.toUpperCase(value);

        if ("PLAINTEXT".equalsIgnoreCase(markup)) {
            return PLAINTEXT;
        }

        if ("MARKDOWN".equalsIgnoreCase(markup)) {
            return MARKDOWN;
        }

        return UNKNOWN;
    }
}
