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

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.internal.Util;

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

    private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile("^.{0,1}[0-9a-z]+$");

    /**
     * A {@link PathPatternBuilder} option.
     */
    public enum Option {
        /**
         * Enum for {@link PathPatternBuilder#startsWith(String)}.
         */
        STARTS_WITH(1, 1),
        /**
         *  Enum for {@link PathPatternBuilder#contains(String)}.
         */
        CONTAINS(2, 1),
        /**
         *  Enum for {@link PathPatternBuilder#endsWith(String)}.
         */
        ENDS_WITH(3, 1),
        /**
         *  Enum for {@link PathPatternBuilder#extension(String)}.
         */
        EXTENSION(3, 1);

        /**
         *  Determines the relative ordering of the group.
         *
         */
        private final int group;

        /**
         * Precedence is the priority  of an option
         * relative to others in the same {@link PathPatternBuilder.Option#group()}.
         * Precedence level (n+1) has a higher precedence than level (n).
         */
        private final int precedence;

        Option(int group, int precedence) {
            this.group = group;
            this.precedence = precedence;
        }

        /**
         * Returns the group order of the option.
         */
        public int group() {
            return group;
        }

        /**
         * Returns the group precedence level of the option.
         */
        public int precedence() {
            return precedence;
        }
    }

    private final class EnrichedPathPattern {
        private final Option option;
        private final PathPattern pathPattern;

        /**
         * Creates a new instance.
         *
         * @param option the option of the enrichedPathPattern
         * @param pathPattern the pathPattern of the enrichedPathPattern
         */
        private EnrichedPathPattern(Option option, PathPattern pathPattern) {
            this.option = option;
            this.pathPattern = pathPattern;
        }

        /**
         * Returns the option of this {@link EnrichedPathPattern}.
         */
        public Option getOption() {
            return option;
        }

        /**
         * Returns the pathPattern of this {@link EnrichedPathPattern}.
         */
        public PathPattern getPathPattern() {
            return pathPattern;
        }
    }

    private final Map<Option, EnrichedPathPattern> patterns = new LinkedHashMap<>();

    /**
     * Prepends "&#47;**&#47;" to {@code filename}.
     * Returns the path pattern for matching file(s) ending in {@code filename}.
     */
    private static PathPattern endsWithPathPattern(String filename) {
        checkArgument(Util.isValidFileName(filename), "filename");
        return new DefaultPathPattern(ImmutableSet.of(filename));
    }

    /**
     * Adds {@link #endsWithPathPattern(String)}.
     */
    public PathPatternBuilder endsWith(String filename) {
        patterns.put(Option.ENDS_WITH,
                     new EnrichedPathPattern(Option.ENDS_WITH, endsWithPathPattern(filename)));
        return this;
    }

    /**
     * Prepends "&#47;**&#47;*" to {@code extension}.
     * Returns the path pattern for matching file(s) ending in {@code extension}.
     */
    private static PathPattern extensionPathPattern(String extension) {
        checkArgument(isValidFileExtension(extension), "extension");
        if (extension.startsWith(".")) {
            return new DefaultPathPattern(ImmutableSet.of("/**/*" + extension));
        } else { // need to add extension separator
            return new DefaultPathPattern(ImmutableSet.of("/**/*." + extension));
        }
    }

    /**
     * Adds {@link #extensionPathPattern(String)}.
     */
    public PathPatternBuilder extension(String extension) {
        patterns.put(Option.EXTENSION,
                     new EnrichedPathPattern(Option.EXTENSION, extensionPathPattern(extension)));
        return this;
    }

    /**
     * Appends "&#47;**" to {@code dirPath}.
     * Returns the path pattern for matching all file(s) under {@code dirPath}.
     */
    private static PathPattern startsWithPathPattern(String dirPath) {
        checkArgument(Util.isValidDirPath(dirPath), "dir");
        return dirPath.endsWith("/") ? new DefaultPathPattern(ImmutableSet.of(dirPath + "**"))
                                     : new DefaultPathPattern(ImmutableSet.of(dirPath + "/**"));
    }

    /**
     * Adds {@link #startsWithPathPattern(String)}}.
     */
    public PathPatternBuilder startsWith(String dirPath) {
        patterns.put(Option.STARTS_WITH,
                     new EnrichedPathPattern(Option.STARTS_WITH, startsWithPathPattern(dirPath)));
        return this;
    }

    /**
     * Prepends and appends "&#47;**" to target {@code dirPath}.
     * Returns the path pattern for matching all file(s) containing {@code dirPath}.
     */
    private static PathPattern containsPathPattern(String dirPath) {
        checkArgument(Util.isValidDirPath(dirPath), "dirPath");
        return dirPath.endsWith("/") ? new DefaultPathPattern(ImmutableSet.of("/**" + dirPath + "**"))
                                     : new DefaultPathPattern(ImmutableSet.of("/**" + dirPath + "/**"));
    }

    /**
     * Adds {@link #containsPathPattern(String)}}.
     */
    public PathPatternBuilder contains(String dirPath) {
        patterns.put(Option.CONTAINS,
                     new EnrichedPathPattern(Option.CONTAINS, containsPathPattern(dirPath)));
        return this;
    }


    /**
     * Returns combined startsWith pathPatternString and endsWith pathPatternString.
     */
    private static String combinePatterns(String left, String right) {
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
            combinedPattern = combinePatterns(combinedPattern, iter.next().patternString());
        }
        return combinedPattern;
    }

    /**
     * Returns a newly-created {@link PathPattern} based on the options of this builder.
     */
    public PathPattern build() {
        checkArgument(!patterns.isEmpty(), "Requires at least one pattern to build in PathPatternBuilder");

        if (patterns.size() == 1) {
            return patterns.entrySet().iterator().next().getValue().getPathPattern();
        }

        final Map<Integer, EnrichedPathPattern> patternsGrouped = new HashMap<>();
        for (Map.Entry<Option, EnrichedPathPattern> entry : patterns.entrySet()) {
            final Option option = entry.getKey();
            final int optionGroup = option.group();
            final int optionPrecedence = option.precedence();
            if (patternsGrouped.containsKey(optionGroup) &&
                optionPrecedence > patternsGrouped.get(optionGroup).getOption().precedence) {
                patternsGrouped.put(optionGroup, entry.getValue());
            } else {
                patternsGrouped.put(optionGroup, entry.getValue());
            }
        }
        final List<PathPattern> collect = patternsGrouped.entrySet()
                                                   .stream()
                                                   .sorted(Map.Entry.comparingByKey())
                                                   .map(Map.Entry::getValue)
                                                   .map(EnrichedPathPattern::getPathPattern)
                                                   .collect(Collectors.toList());
        return new DefaultPathPattern(ImmutableSet.of(combine(collect)));
    }

    private static boolean isValidFileExtension(String extension) {
        checkArgument(!extension.isEmpty(), "extension");
        return FILE_EXTENSION_PATTERN.matcher(extension).matches();
    }
}
