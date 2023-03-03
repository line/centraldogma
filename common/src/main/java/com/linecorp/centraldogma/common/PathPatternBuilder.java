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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

/**
 * Builds a new {@link PathPattern}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * final PathPattern pathPattern =
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
        requireNonNull(filename, "filename");
        endPattern = PathPatternOptions.ENDS_WITH.apply(filename);
        return this;
    }

    /**
     * Adds {@link PathPatternOptions#EXTENSION}.
     */
    public PathPatternBuilder extension(String extension) {
        requireNonNull(extension, "extension");
        endPattern = PathPatternOptions.EXTENSION.apply(extension);
        return this;
    }

    /**
     * Adds {@link PathPatternOptions#STARTS_WITH}.
     */
    public PathPatternBuilder startsWith(String dirPath) {
        requireNonNull(dirPath, "dirPath");
        startPattern = PathPatternOptions.STARTS_WITH.apply(dirPath);
        return this;
    }

    /**
     * Adds {@link PathPatternOptions#CONTAINS}.
     */
    public PathPatternBuilder contains(String dirPath) {
        requireNonNull(dirPath, "dirPath");
        innerPatterns.add(PathPatternOptions.CONTAINS.apply(dirPath));
        return this;
    }

    /**
     * Compose one pathPattern from a list of {@code patterns}.
     */
    private static String combine(List<PathPattern> patterns) {
        final StringBuilder sb = new StringBuilder();
        for (final Iterator<PathPattern> i = patterns.iterator(); i.hasNext();) {
            if (sb.length() == 0) {
                // left should end with "/**"
                sb.append(i.next().patternString());
            } else {
                // right should start with "/**/"
                sb.append(i.next().patternString().substring(3));
            }
        }
        return sb.toString();
    }

    /**
     * Returns a newly-created {@link PathPattern} based on the options of this builder.
     */
    public PathPattern build() {
        final ImmutableList.Builder<PathPatternOption> optionsBuilder = ImmutableList.builder();
        if (startPattern != null) {
            optionsBuilder.add(startPattern);
        }
        optionsBuilder.addAll(innerPatterns);
        if (endPattern != null) {
            optionsBuilder.add(endPattern);
        }
        final ImmutableList<PathPatternOption> options = optionsBuilder.build();

        checkState(!options.isEmpty(), "Requires at least one pattern to build in PathPatternBuilder");

        if (options.size() == 1) {
            return options.get(0).pathPattern();
        }

        final List<PathPattern> patterns = options.stream()
                                                  .map(PathPatternOption::pathPattern)
                                                  .collect(toImmutableList());
        return new DefaultPathPattern(combine(patterns));
    }
}
