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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.centraldogma.internal.Util;

public class Revision implements Comparable<Revision> {

    private static final Pattern REVISION_PATTERN = Pattern.compile("^(-?[0-9]+)(?:\\.([0-9]+))?$");

    // Use integer negative to init revision number as the relative revision.
    public static final Revision HEAD = new Revision(-1);

    public static final Revision INIT = new Revision(1);

    private final int major;
    private final int minor;
    private final String text;

    public Revision(int major) {
        this(major, 0);
    }

    @JsonCreator
    public Revision(@JsonProperty("major") int major,
                    @JsonProperty(value = "minor", defaultValue = "0") int minor) {

        if (major == 0) {
            throw new IllegalArgumentException("major: 0 (expected: a non-zero integer)");
        }

        this.major = major;
        this.minor = minor;
        text = generateText(major, minor);
    }

    /**
     * Create a new Revision object from a string representation (major.minor).
     *
     * @param revisionStr string representation of a revision
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

    @JsonProperty
    public int major() {
        return major;
    }

    @JsonProperty
    public int minor() {
        return minor;
    }

    public String text() {
        return text;
    }

    public boolean onMainLane() {
        return minor() == 0;
    }

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

        if (minor < o.minor()) {
            return -1;
        }
        if (minor > o.minor()) {
            return 1;
        }

        return 0;
    }

    boolean isMajorRelative() {
        return major < 0;
    }

    boolean isMinorRelative() {
        return minor < 0;
    }

    @JsonIgnore
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
