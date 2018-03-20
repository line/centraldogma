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

import com.google.common.base.Converter;

/**
 * Provides a function converting back and forth between {@link Markup} and
 * {@link com.linecorp.centraldogma.common.Markup}.
 */
public final class MarkupConverter extends Converter<com.linecorp.centraldogma.common.Markup, Markup> {
    public static final Converter<com.linecorp.centraldogma.common.Markup, Markup> TO_DATA =
            new MarkupConverter();

    public static final Converter<Markup, com.linecorp.centraldogma.common.Markup> TO_MODEL =
            TO_DATA.reverse();

    private MarkupConverter() {
    }

    @Override
    protected Markup doForward(com.linecorp.centraldogma.common.Markup markup) {
        switch (markup) {
            case PLAINTEXT:
                return Markup.PLAINTEXT;
            case MARKDOWN:
                return Markup.MARKDOWN;
            default:
                return Markup.UNKNOWN;
        }
    }

    @Override
    protected com.linecorp.centraldogma.common.Markup doBackward(Markup markup) {
        switch (markup) {
            case PLAINTEXT:
                return com.linecorp.centraldogma.common.Markup.PLAINTEXT;
            case MARKDOWN:
                return com.linecorp.centraldogma.common.Markup.MARKDOWN;
            default:
                return com.linecorp.centraldogma.common.Markup.UNKNOWN;
        }
    }
}
