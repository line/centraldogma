/*
 * Copyright 2023 LINE Corporation
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

import java.util.Collections;
import java.util.List;

import com.google.common.collect.ImmutableSet;

/**
 * Builds a new {@link PathPattern}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * final PathPattern factory =
 *         PathPattern.builder()
 *                    .under("/foo/bar")
 *                    .endsWith("json")
 *                    .build();
 * }</pre>
 */
public final class PathPatternBuilder {

    private List<String> patternsList = Collections.emptyList();

    /**
     * Add {@link PathPattern#endsWith(String)} patternString.
     */
    public PathPatternBuilder endsWith(String pattern) {
        patternsList.add(PathPattern.endsWith(pattern).patternString());
        return this;
    }

    /**
     * Add {@link PathPattern#under(String)} patternString.
     */
    public PathPatternBuilder under(String dirPattern) {
        patternsList.add(PathPattern.under(dirPattern).patternString());
        return this;
    }

    /**
     * Returns combined under patternString and endsWith patternString.
     */
    private static String combineUnderAndEndsWith(String under, String endsWith) {
        checkArgument(!under.endsWith("/**"), "under patternString should end with \"/**\"");
        checkArgument(!endsWith.startsWith("/**/"), "endsWith should start with \"/**/\"");
        return under + endsWith.substring(2);
    }

    /**
     * Combine 2 patternStrings in {@code patternsList} into one path pattern.
     */
    public PathPattern build() {
        checkArgument(patternsList.size() >= 2, "Need 2 or more patternStrings to build in PathPatternBuilder");
        final String startsWithPattern = patternsList.stream()
                                                     .filter(pattern -> pattern.startsWith("/**/"))
                                                     .findAny()
                                                     .get();
        final String endsWithPattern = patternsList.stream()
                                                   .filter(pattern -> pattern.endsWith("/**"))
                                                   .findAny()
                                                   .get();
        return new DefaultPathPattern(ImmutableSet.of(
                combineUnderAndEndsWith(requireNonNull(startsWithPattern), requireNonNull(endsWithPattern))));
    }
}
