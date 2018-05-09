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

import java.time.Instant;
import java.util.Collections;

import com.google.common.base.Converter;

import com.linecorp.centraldogma.common.Markup;

/**
 * Provides a function converting back and forth between {@link Commit} and
 * {@link com.linecorp.centraldogma.common.Commit}.
 */
public final class CommitConverter extends Converter<com.linecorp.centraldogma.common.Commit, Commit> {
    public static final Converter<com.linecorp.centraldogma.common.Commit, Commit> TO_DATA =
            new CommitConverter();

    public static final Converter<Commit, com.linecorp.centraldogma.common.Commit> TO_MODEL =
            TO_DATA.reverse();

    private CommitConverter() {}

    @Override
    protected Commit doForward(com.linecorp.centraldogma.common.Commit commit) {
        final Comment comment = new Comment();
        comment.setContent(commit.detail());
        comment.setMarkup(MarkupConverter.TO_DATA.convert(commit.markup()));
        return new Commit(RevisionConverter.TO_DATA.convert(commit.revision()),
                          AuthorConverter.TO_DATA.convert(commit.author()),
                          commit.whenAsText(),
                          commit.summary(), comment, Collections.emptyList());
    }

    @Override
    protected com.linecorp.centraldogma.common.Commit doBackward(Commit commit) {
        final Markup markup = Markup.valueOf(commit.getDetail().getMarkup().name());
        return new com.linecorp.centraldogma.common.Commit(
                RevisionConverter.TO_MODEL.convert(commit.getRevision()),
                AuthorConverter.TO_MODEL.convert(commit.getAuthor()),
                Instant.parse(commit.getTimestamp()).toEpochMilli(),
                commit.getSummary(),
                commit.getDetail().getContent(),
                markup);
    }
}
