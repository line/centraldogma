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

import java.util.regex.Matcher;
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

    private static final Pattern REVISION_PATTERN = Pattern.compile("^(-?[0-9]+)(?:\\.([0-9]+))?$");

    /**
     * Revision {@code -1}, also known as 'HEAD'.
     */
    public static final Revision HEAD = new Revision(-1);

    /**
     * Revision {@code 1}, also known as 'INIT'.
     */
    public static final Revision INIT = new Revision(1);

    private final int major;
    private final int minor;
    private final String text;

    /**
     * Creates a new instance with the specified revision number.
     */
    public Revision(int major) {
        this(major, 0);
    }

    /**
     * Creates a new instance.
     *
     * @deprecated Use {@link #Revision(int)} instead. Minor revisions are not used anymore.
     */
    @Deprecated
    public Revision(int major, int minor) {
        if (major == 0) {
            throw new IllegalArgumentException("major: 0 (expected: a non-zero integer)");
        }

        this.major = major;
        this.minor = minor;
        text = generateText(major, minor);
    }

    /**
     * Create a new instance from a string representation. e.g. {@code "42", "-1"}
     */
    public Revision(String revisionStr) {
        requireNonNull(revisionStr, "revisionStr");
        if ("head".equalsIgnoreCase(revisionStr) || "-1".equals(revisionStr)) {
            major = HEAD.major;
            minor = HEAD.minor;
        } else {
            Matcher m = REVISION_PATTERN.matcher(revisionStr);
            if (!m.matches()) {
                throw illegalRevisionArgumentException(revisionStr);
            }
            String majorStr = m.group(1);
            String minorStr = m.group(2);
            try {
                major = Integer.parseInt(majorStr);
                if (major == 0) {
                    throw illegalRevisionArgumentException(revisionStr);
                }
                minor = minorStr == null ? 0 : Integer.parseInt(minorStr);
            } catch (NumberFormatException ignored) {
                throw illegalRevisionArgumentException(revisionStr);
            }
        }
        text = generateText(major, minor);
    }

    /**
     * Returns the revision number.
     */
    public int major() {
        return major;
    }

    /**
     * Returns the minor revision number.
     *
     * @deprecated Do not use. Minor revisions are not used anymore.
     */
    @Deprecated
    public int minor() {
        return minor;
    }

    /**
     * Returns the textual representation of the revision. e.g. {@code "42", "-1"}.
     */
    public String text() {
        return text;
    }

    /**
     * Returns whether the minor revision is zero.
     *
     * @deprecated Do not use. Minor revisions are not used anymore.
     */
    @Deprecated
    public boolean onMainLane() {
        return minor() == 0;
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

        if (minor == 0) {
            return new Revision(subtract(major, count));
        } else {
            return new Revision(major, subtract(minor, count));
        }
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

        if (minor == 0) {
            return new Revision(add(major, count));
        } else {
            return new Revision(major, add(minor, count));
        }
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
        return major * 31 + minor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Revision)) {
            return false;
        }

        Revision revision = (Revision) o;

        return major == revision.major && minor == revision.minor;
    }

    @Override
    public String toString() {

        final StringBuilder buf = new StringBuilder();

        buf.append(Util.simpleTypeName(this));
        buf.append('(');
        buf.append(major);
        if (minor != 0) {
            buf.append('.');
            buf.append(minor);
        }
        buf.append(')');

        return buf.toString();
    }

    @Override
    public int compareTo(Revision o) {
        if (major < o.major()) {
            return -1;
        }
        if (major > o.major()) {
            return 1;
        }

        return Integer.compare(minor, o.minor());
    }

    boolean isMajorRelative() {
        return major < 0;
    }

    boolean isMinorRelative() {
        return minor < 0;
    }

    /**
     * Returns whether this {@link Revision} is relative.
     */
    public boolean isRelative() {
        return isMajorRelative() || isMinorRelative();
    }

    private static String generateText(int major, int minor) {
        return minor == 0 ? String.valueOf(major) : major + "." + minor;
    }

    private static IllegalArgumentException illegalRevisionArgumentException(String revisionStr) {
        return new IllegalArgumentException(
                "revisionStr: " + revisionStr +
                " (expected: \"major\" or \"major.minor\"" +
                " where major is non-zero integer and minor is an integer)");
    }
}
