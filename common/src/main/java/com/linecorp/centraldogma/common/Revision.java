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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import com.linecorp.centraldogma.internal.Util;

/**
 * A revision number of a {@link Commit}.
 *
 * <p>A revision number is an integer which refers to a specific point of repository history.
 * When a repository is created, it starts with an initial commit whose revision is {@code 1}.
 * As new commits are added, each commit gets its own revision number, monotonically increasing from the
 * previous commit's revision. i.e. 1, 2, 3, ...
 *
 * <p>A revision number can also be represented as a negative integer. When a revision number is negative,
 * we start from {@code -1} which refers to the latest commit in repository history, which is often called
 * 'HEAD' of the repository. A smaller revision number refers to the older commit. e.g. -2 refers to the
 * commit before the latest commit, and so on.
 *
 * <p>A revision with a negative integer is called 'relative revision'. By contrast, a revision with
 * a positive integer is called 'absolute revision'.
 */
@JsonSerialize(using = RevisionJsonSerializer.class)
@JsonDeserialize(using = RevisionJsonDeserializer.class)
public class Revision implements Comparable<Revision> {

    private static final Pattern REVISION_PATTERN = Pattern.compile("^-?[0-9]+(?:\\.0)?$");

    /**
     * Revision {@code -1}, also known as 'HEAD'.
     */
    public static final Revision HEAD = new Revision(-1);

    /**
     * Revision {@code 1}, also known as 'INIT'.
     */
    public static final Revision INIT = new Revision(1);

    private final int major;
    private final String text;

    /**
     * Creates a new instance with the specified revision number.
     */
    public Revision(int major) {
        if (major == 0) {
            throw new IllegalArgumentException("major: 0 (expected: a non-zero integer)");
        }

        this.major = major;
        text = generateText(major);
    }

    /**
     * Creates a new instance.
     *
     * @deprecated Use {@link #Revision(int)} instead. Minor revisions are not used anymore.
     */
    @Deprecated
    public Revision(int major, int minor) {
        this(major);
        checkArgument(minor == 0, "minor: %s (expected: 0)", minor);
    }

    /**
     * Create a new instance from a string representation. e.g. {@code "42", "-1"}
     */
    public Revision(String revisionStr) {
        requireNonNull(revisionStr, "revisionStr");
        if ("head".equalsIgnoreCase(revisionStr) || "-1".equals(revisionStr)) {
            major = HEAD.major;
        } else {
            if (!REVISION_PATTERN.matcher(revisionStr).matches()) {
                throw illegalRevisionArgumentException(revisionStr);
            }

            try {
                major = Integer.parseInt(
                        !revisionStr.endsWith(".0") ? revisionStr
                                                    : revisionStr.substring(0, revisionStr.length() - 2));
                if (major == 0) {
                    throw illegalRevisionArgumentException(revisionStr);
                }
            } catch (NumberFormatException ignored) {
                throw illegalRevisionArgumentException(revisionStr);
            }
        }
        text = generateText(major);
    }

    /**
     * Returns the revision number.
     */
    public int major() {
        return major;
    }

    /**
     * Returns {@code 0}.
     *
     * @deprecated Do not use. Minor revisions are not used anymore.
     */
    @Deprecated
    public int minor() {
        return 0;
    }

    /**
     * Returns the textual representation of the revision. e.g. {@code "42", "-1"}.
     */
    public String text() {
        return text;
    }

    /**
     * Returns {@code true}.
     *
     * @deprecated Do not use. Minor revisions are not used anymore.
     */
    @Deprecated
    public boolean onMainLane() {
        return true;
    }

    /**
     * Returns a new {@link Revision} whose revision number is earlier than this {@link Revision}.
     *
     * @param count the number of commits to go backward
     */
    public Revision backward(int count) {
        if (count == 0) {
            return this;
        }
        if (count < 0) {
            throw new IllegalArgumentException("count " + count + " (expected: a non-negative integer)");
        }

        return new Revision(subtract(major, count));
    }

    private static int subtract(int revNum, int delta) {
        if (revNum > 0) {
            return Math.max(1, revNum - delta);
        } else {
            if (revNum < Integer.MIN_VALUE + delta) {
                return Integer.MIN_VALUE;
            } else {
                return revNum - delta;
            }
        }
    }

    /**
     * Returns a new {@link Revision} whose revision number is later than this {@link Revision}.
     *
     * @param count the number of commits to go forward
     */
    public Revision forward(int count) {
        if (count == 0) {
            return this;
        }
        if (count < 0) {
            throw new IllegalArgumentException("count " + count + " (expected: a non-negative integer)");
        }

        return new Revision(add(major, count));
    }

    private static int add(int revNum, int delta) {
        if (revNum > 0) {
            if (revNum > Integer.MAX_VALUE - delta) {
                return Integer.MAX_VALUE;
            } else {
                return revNum + delta;
            }
        } else {
            return Math.min(-1, revNum + delta);
        }
    }

    @Override
    public int hashCode() {
        return major;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Revision)) {
            return false;
        }

        return major == ((Revision) o).major;
    }

    @Override
    public String toString() {

        final StringBuilder buf = new StringBuilder();

        buf.append(Util.simpleTypeName(this));
        buf.append('(');
        buf.append(major);
        buf.append(')');

        return buf.toString();
    }

    @Override
    public int compareTo(Revision o) {
        return Integer.compare(major, o.major);
    }

    /**
     * Returns whether this {@link Revision} is relative.
     */
    public boolean isRelative() {
        return major < 0;
    }

    private static String generateText(int major) {
        return String.valueOf(major);
    }

    private static IllegalArgumentException illegalRevisionArgumentException(String revisionStr) {
        return new IllegalArgumentException(
                "revisionStr: " + revisionStr +
                " (expected: \"major\" or \"major.0\" where major is non-zero integer)");
    }
}
