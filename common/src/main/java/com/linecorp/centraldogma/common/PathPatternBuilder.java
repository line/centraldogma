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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

/**
 * Builds a new {@link PathPattern}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * final PathPattern factory =
 *         PathPattern.builder()
 *                    .startsWith("/foo/bar")
 *                    .contains("/ext")
 *                    .extension("json")
 *                    .build();
 * }</pre>
 */
public final class PathPatternBuilder {
    @Nullable
    private PathPatternOption startPattern;
    private final List<PathPatternOption> innerPatterns = new ArrayList<>();
    @Nullable
    private PathPatternOption endPattern;
    PathPatternBuilder() {}
    /**
     * Adds {@link PathPatternOptions#ENDS_WITH}.
     */
    public PathPatternBuilder endsWith(String filename) {
        endPattern = PathPatternOptions.ENDS_WITH.apply(filename);
        return this;
    }

    /**
     * Adds {@link PathPatternOptions#EXTENSION}.
     */
    public PathPatternBuilder extension(String extension) {
        endPattern = PathPatternOptions.EXTENSION.apply(extension);
        return this;
    }

    /**
     * Adds {@link PathPatternOptions#STARTS_WITH}.
     */
    public PathPatternBuilder startsWith(String dirPath) {
        startPattern = PathPatternOptions.STARTS_WITH.apply(dirPath);
        return this;
    }

    /**
     * Adds {@link PathPatternOptions#CONTAINS}.
     */
    public PathPatternBuilder contains(String dirPath) {
        innerPatterns.add(PathPatternOptions.CONTAINS.apply(dirPath));
        return this;
    }

    /**
     * Combine 2 patterns into one.
     */
    private static String combine(String left, String right) {
        checkArgument(left.endsWith("/**"), "left should end with \"/**\"");
        checkArgument(right.startsWith("/**/"), "right should start with \"/**/\"");
        return left + right.substring(3);
    }

    /**
     * Compose one pathPattern from a list of {@code patterns}.
     */
    private static String combine(List<PathPattern> patterns) {
        final Iterator<PathPattern> iter = patterns.iterator();
        String combinedPattern = iter.next().patternString();
        while (iter.hasNext()) {
            combinedPattern = combine(combinedPattern, iter.next().patternString());
        }
        return combinedPattern;
    }

    /**
     * Returns a newly-created {@link PathPattern} based on the options of this builder.
     */
    public PathPattern build() {
        final List<PathPatternOption> options = new ArrayList<>();
        if (startPattern != null) {
            options.add(startPattern);
        }
        options.addAll(innerPatterns);
        if (endPattern != null) {
            options.add(endPattern);
        }

        checkArgument(!options.isEmpty(), "Requires at least one pattern to build in PathPatternBuilder");

        if (options.size() == 1) {
            return options.get(0).pathPattern();
        }

        final List<PathPattern> patterns = options.stream()
                                                  .map(PathPatternOption::pathPattern)
                                                  .collect(toImmutableList());
        return new DefaultPathPattern(ImmutableSet.of(combine(patterns)));
    }
}