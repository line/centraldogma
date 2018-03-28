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
 * Provides a function converting back and forth between {@link Revision} and
 * {@link com.linecorp.centraldogma.common.Revision}.
 */
public final class RevisionConverter extends Converter<com.linecorp.centraldogma.common.Revision, Revision> {
    public static final Converter<com.linecorp.centraldogma.common.Revision, Revision> TO_DATA =
            new RevisionConverter();

    public static final Converter<Revision, com.linecorp.centraldogma.common.Revision> TO_MODEL =
            TO_DATA.reverse();

    private RevisionConverter() {
    }

    @Override
    protected Revision doForward(com.linecorp.centraldogma.common.Revision rev) {
        return new Revision(rev.major(), 0);
    }

    @Override
    protected com.linecorp.centraldogma.common.Revision doBackward(Revision rev) {
        if (rev.getMinor() != 0) {
            throw new IllegalArgumentException("minor: " + rev.getMinor() + " (expected: 0)");
        }
        return new com.linecorp.centraldogma.common.Revision(rev.getMajor());
    }
}
