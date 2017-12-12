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

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.linecorp.centraldogma.internal.Util;

/**
 * A set of {@link Change}s and its metadata.
 */
public class Commit {

    private final Revision revision;
    private final Author author;
    private final long when;
    private final String summary;
    private final String detail;
    private final Markup markup;
    private String whenAsText;

    /**
     * Creates a new instance. The time and date of the new {@link Commit} is set to the current time and date.
     *
     * @param revision the {@link Revision} of this {@link Commit}
     * @param author the {@link Author} of this {@link Commit}
     * @param summary the human-readable summary of this {@link Commit}
     * @param detail the human-readable detailed description of this {@link Commit}
     * @param markup the {@link Markup} language of {@code summary} and {@code detail}
     */
    public Commit(Revision revision, Author author, String summary, String detail, Markup markup) {
        this(revision, author, System.currentTimeMillis(), summary, detail, markup);
    }

    /**
     * Creates a new instance.
     *
     * @param revision the {@link Revision} of this {@link Commit}
     * @param author the {@link Author} of this {@link Commit}
     * @param when the time and date of this {@link Commit},
     *             represented as the number of milliseconds since the epoch (midnight, January 1, 1970 UTC)
     * @param summary the human-readable summary of this {@link Commit}
     * @param detail the human-readable detailed description of this {@link Commit}
     * @param markup the {@link Markup} language of {@code summary} and {@code detail}
     */
    public Commit(Revision revision, Author author, long when, String summary, String detail, Markup markup) {
        this.revision = requireNonNull(revision, "revision");
        this.author = requireNonNull(author, "author");
        this.summary = requireNonNull(summary, "summary");
        this.detail = requireNonNull(detail, "detail");
        this.markup = requireNonNull(markup, "markup");
        this.when = when / 1000L * 1000L; // Drop the milliseconds
    }

    /**
     * Returns the {@link Revision} of this {@link Commit}.
     */
    public Revision revision() {
        return revision;
    }

    /**
     * Returns the time and date of this {@link Commit}.
     *
     * @return the number of milliseconds since the epoch (midnight, January 1, 1970 UTC)
     */
    public long when() {
        return when;
    }

    /**
     * Returns the time and date of this {@link Commit} in
     * <a href="https://en.wikipedia.org/wiki/ISO_8601#Combined_date_and_time_representations">ISO 8601
     * combined date and time representation</a>.
     */
    public String whenAsText() {
        if (whenAsText == null) {
            whenAsText = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(when()));
        }
        return whenAsText;
    }

    /**
     * Returns the {@link Author} of this {@link Commit}.
     */
    public Author author() {
        return author;
    }

    /**
     * Returns the human-readable summary of this {@link Commit}.
     */
    public String summary() {
        return summary;
    }

    /**
     * Returns the human-readable detailed description of this {@link Commit}.
     */
    public String detail() {
        return detail;
    }

    /**
     * Returns the {@link Markup} language of {@link #summary()} and {@link #detail()}.
     */
    public Markup markup() {
        return markup;
    }

    @Override
    public int hashCode() {
        return (revision.hashCode() * 31 + author.hashCode()) * 31 + (int) (when / 1000);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Commit)) {
            return false;
        }

        final Commit that = (Commit) o;
        return revision.equals(that.revision) && author.equals(that.author) && when == that.when &&
               summary.equals(that.summary) && detail.equals(that.detail) && markup == that.markup;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder(128);

        buf.append(Util.simpleTypeName(this));
        buf.append('[');
        buf.append(revision.major());
        buf.append(": author=");
        buf.append(author.email());
        buf.append(", when=");
        buf.append(whenAsText());
        buf.append(", summary=");
        buf.append(summary);
        buf.append(']');

        return buf.toString();
    }
}
