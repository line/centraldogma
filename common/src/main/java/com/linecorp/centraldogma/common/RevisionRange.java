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

import com.google.common.base.MoreObjects;

/**
 * A class which contains {@link Revision}s of {@code from} and {@code to}.
 */
public class RevisionRange {

    private final Revision from;

    private final Revision to;

    /**
     * Creates a new instance with the specified integer value of {@code from} and {@code to}.
     */
    public RevisionRange(int from, int to) {
        this(new Revision(from), new Revision(to));
    }

    /**
     * Creates a new instance with the specified {@code from} {@link Revision} and {@code to} {@link Revision}.
     */
    public RevisionRange(Revision from, Revision to) {
        this.from = requireNonNull(from, "from");
        this.to = requireNonNull(to, "to");
    }

    /**
     * Returns the {@code from} {@link Revision}.
     */
    public Revision from() {
        return from;
    }

    /**
     * Returns the {@code to} {@link Revision}.
     */
    public Revision to() {
        return to;
    }

    /**
     * Returns the {@link RevisionRange} whose major value of {@code from} {@link Revision} is lower than
     * or equal to the major value of {@code to} {@link Revision}.
     *
     * @throws IllegalStateException if the {@code from} and {@code to} {@link Revision}s are in the
     *                               different state. They should be either absolute or relative.
     */
    public RevisionRange toAscending() {
        if (isAscending() || from.equals(to)) {
            return this;
        }

        return new RevisionRange(to, from);
    }

    /**
     * Returns the {@link RevisionRange} whose major value of {@code from} {@link Revision} is greater than
     * or equal to the major value of {@code to} {@link Revision}.
     *
     * @throws IllegalStateException if the {@code from} and {@code to} {@link Revision}s are in the
     *                               different state. They should be either absolute or relative.
     */
    public RevisionRange toDescending() {
        if (isAscending()) {
            return new RevisionRange(to, from);
        }

        return this;
    }

    /**
     * Returns {@code true} if the major value of {@code from} {@link Revision} is lower than the major
     * value of {@code to} {@link Revision}.
     *
     * @throws IllegalStateException if the {@code from} and {@code to} {@link Revision}s are in the
     *                               different state. They should be either absolute or relative.
     */
    public boolean isAscending() {
        if (from.isRelative() != to.isRelative()) {
            throw new IllegalStateException("both of from: '" + from + "' and to: '" + to +
                                            "' should be absolute or relative.");
        }

        return from.compareTo(to) < 0;
    }

    /**
     * Returns {@code true} if the major value of {@code from} {@link Revision} or {@code to} {@link Revision}
     * is a negative integer.
     */
    public boolean isRelative() {
        return from.isRelative() || to.isRelative();
    }

    @Override
    public int hashCode() {
        return from.hashCode() * 31 + to.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final RevisionRange that = (RevisionRange) o;
        return from.equals(that.from) && to.equals(that.to);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("from", from)
                          .add("to", to)
                          .toString();
    }
}
