/*
 * Copyright 2018 LINE Corporation
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

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

/**
 * The result of a {@code push} operation.
 */
public class PushResult {

    private final Revision revision;
    private final long when;
    @Nullable
    private String whenAsText;

    /**
     * Creates a new instance.
     *
     * @param revision the {@link Revision} of the pushed commit
     * @param when the time and date of the pushed commit, represented as the number of milliseconds
     *             since the epoch (midnight, January 1, 1970 UTC)
     */
    public PushResult(Revision revision, long when) {
        this.revision = requireNonNull(revision, "revision");
        this.when = when / 1000L * 1000L; // Drop the milliseconds
    }

    /**
     * Returns the {@link Revision} of the pushed commit.
     */
    public Revision revision() {
        return revision;
    }

    /**
     * Returns the time and date of the pushed commit.
     *
     * @return the number of milliseconds since the epoch (midnight, January 1, 1970 UTC)
     */
    public long when() {
        return when;
    }

    /**
     * Returns the time and date of the pushed commit in
     * <a href="https://en.wikipedia.org/wiki/ISO_8601#Combined_date_and_time_representations">ISO 8601
     * combined date and time representation</a>.
     */
    public String whenAsText() {
        if (whenAsText == null) {
            whenAsText = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(when()));
        }
        return whenAsText;
    }

    @Override
    public int hashCode() {
        return revision.hashCode() * 31 + (int) (when / 1000);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PushResult)) {
            return false;
        }

        final PushResult that = (PushResult) o;
        return revision.equals(that.revision) && when == that.when;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .addValue(revision.text())
                          .add("when", whenAsText())
                          .toString();
    }
}
