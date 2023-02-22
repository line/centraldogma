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

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.common.PathPatternOptions.PathPatternOptionFactory;

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

    private final Map<String, PathPatternOption> options = new LinkedHashMap<>();

    /**
     * Adds {@link PathPatternOptions#ENDS_WITH_OPTION}.
     */
    public PathPatternBuilder endsWith(String filename) {
        put(PathPatternOptions.ENDS_WITH_OPTION, filename);
        return this;
    }

    /**
     * Adds {@link PathPatternOptions#EXTENSION_OPTION}.
     */
    public PathPatternBuilder extension(String extension) {
        put(PathPatternOptions.EXTENSION_OPTION, extension);
        return this;
    }

    /**
     * Adds {@link PathPatternOptions#STARTS_WITH_OPTION}.
     */
    public PathPatternBuilder startsWith(String dirPath) {
        put(PathPatternOptions.STARTS_WITH_OPTION, dirPath);
        return this;
    }

    /**
     * Adds {@link PathPatternOptions#CONTAINS_OPTION}.
     */
    public PathPatternBuilder contains(String dirPath) {
        put(PathPatternOptions.CONTAINS_OPTION, dirPath);
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
    private static String combine(Iterable<PathPattern> patterns) {
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
        checkArgument(!options.isEmpty(), "Requires at least one pattern to build in PathPatternBuilder");

        if (options.size() == 1) {
            return options.entrySet().iterator().next().getValue().getPathPattern();
        }
        // given the same precedence, option added after overrides previous one
        final Map<Integer, PathPatternOption> optionByPrecedence = options.values()
                                                                    .stream()
                                                                    .collect(Collectors.toMap(
                                                                            PathPatternOption::getPrecedence,
                                                                            Function.identity(),
                                                                            (a, b) -> b));
        final List<PathPattern> patterns = optionByPrecedence.values()
                                                       .stream()
                                                       .sorted(Comparator.comparing(
                                                               PathPatternOption::getPrecedence))
                                                       .map(PathPatternOption::getPathPattern)
                                                       .collect(Collectors.toList());

        return new DefaultPathPattern(ImmutableSet.of(combine(patterns)));
    }

    /**
     * Adds the specified {@link PathPatternOption}.
     */
    private void put(PathPatternOptionFactory optionHelper, String pattern) {
        final PathPatternOption pathPatternOption = optionHelper.create(pattern);
        checkArgument(!options.containsKey(pathPatternOption.getName()), " %s option exists",
                      pathPatternOption.getName());
        options.put(pathPatternOption.getName(), pathPatternOption);
    }
}
