/*
 * Copyright 2021 LINE Corporation
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

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.math.IntMath;

final class DefaultPathPattern implements PathPattern {

    private static final Pattern PATH_PATTERN_PATTERN = Pattern.compile("^[- /*_.0-9a-zA-Z]+$");

    static final String ALL = "/**";

    static final DefaultPathPattern allPattern = new DefaultPathPattern(ALL, ALL);

    private final String patterns;

    @Nullable
    private String encoded;

    DefaultPathPattern(Set<String> patterns) {
        this.patterns = patterns.stream()
                                .peek(DefaultPathPattern::validatePathPattern)
                                .filter(pattern -> !pattern.isEmpty())
                                .map(pattern -> {
                                    if (pattern.charAt(0) != '/') {
                                        return "/**/" + pattern;
                                    }
                                    return pattern;
                                }).collect(Collectors.joining(","));
    }

    DefaultPathPattern(List<PathPattern> verifiedPatterns) {
        patterns = verifiedPatterns.stream()
                                   .map(PathPattern::patternString)
                                   .collect(Collectors.joining(","));
    }

    private DefaultPathPattern(String patterns, String encoded) {
        this.patterns = patterns;
        this.encoded = encoded;
    }

    @Override
    public String patternString() {
        return patterns;
    }

    @Override
    public String encoded() {
        if (encoded != null) {
            return encoded;
        }
        return encoded = encodePathPattern(patterns);
    }

    @VisibleForTesting
    static String encodePathPattern(String pathPattern) {
        // We do not need full escaping because we validated the path pattern already and thus contains only
        // -, ' ', /, *, _, ., ',', a-z, A-Z, 0-9.
        int spacePos = pathPattern.indexOf(' ');
        if (spacePos < 0) {
            return pathPattern;
        }

        final StringBuilder buf = new StringBuilder(IntMath.saturatedMultiply(pathPattern.length(), 2));
        for (int pos = 0;;) {
            buf.append(pathPattern, pos, spacePos);
            buf.append("%20");
            pos = spacePos + 1;
            spacePos = pathPattern.indexOf(' ', pos);
            if (spacePos < 0) {
                buf.append(pathPattern, pos, pathPattern.length());
                break;
            }
        }

        return buf.toString();
    }

    private static String validatePathPattern(String pattern) {
        checkArgument(PATH_PATTERN_PATTERN.matcher(pattern).matches(),
                      "pattern: %s (expected: %s)", pattern, PATH_PATTERN_PATTERN);
        return pattern;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("patterns", patterns)
                          .add("encoded", encoded)
                          .toString();
    }
}
