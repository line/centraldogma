package com.linecorp.centraldogma.common;

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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.regex.Pattern;

import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.internal.Util;

/**
 * A set of {@link PathPatternOption}s.
 */
final class PathPatternOptions {

    private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile("^.{0,1}[0-9a-z]+$");

    private PathPatternOptions() {}

    /**
     * A factory to create an {@link PathPatternOption} instance.
     */
    @FunctionalInterface
    public interface PathPatternOptionFactory {
        /**
         * Creates a new {@link PathPatternOption} for {@code pattern}.
         */
        PathPatternOption create(String pattern);
    }

    /**
     * Appends "&#47;**" to {@code dirPath}.
     * Returns the path pattern for matching all file(s) under {@code dirPath}.
     */
    public static final PathPatternOptionFactory STARTS_WITH_OPTION = pattern ->
            new PathPatternOption(1,
                                  "startsWith",
                                  pattern,
                                  dirPath -> {
                                      checkArgument(Util.isValidDirPath(dirPath), "dir");
                                      return new DefaultPathPattern(ImmutableSet.of(
                                              dirPath + (dirPath.endsWith("/") ? "" : "/") + "**"));
                                  });

    /**
     * Prepends and appends "&#47;**" to target {@code dirPath}.
     * Returns the path pattern for matching all file(s) containing {@code dirPath}.
     */
    public static final PathPatternOptionFactory CONTAINS_OPTION = pattern ->
            new PathPatternOption(2,
                                  "contains",
                                  pattern,
                                  dirPath -> {
                                      checkArgument(Util.isValidDirPath(dirPath), "dirPath");
                                      return dirPath.endsWith("/") ? new DefaultPathPattern(
                                              ImmutableSet.of("/**" + dirPath + "**"))
                                                                   : new DefaultPathPattern(
                                              ImmutableSet.of("/**" + dirPath + "/**"));
                                  });

    /**
     * Prepends "&#47;**&#47;" to {@code filename}.
     * Returns the path pattern for matching file(s) ending in {@code filename}.
     */
    public static final PathPatternOptionFactory ENDS_WITH_OPTION = pattern ->
            new PathPatternOption(3,
                                  "endsWith",
                                  pattern,
                                  filename -> {
                                      checkArgument(Util.isValidFileName(filename), "filename");
                                      return new DefaultPathPattern(ImmutableSet.of(filename));
                                  });

    /**
     * Prepends "&#47;**&#47;*" to {@code extension}.
     * Returns the path pattern for matching file(s) ending in {@code extension}.
     */
    public static final PathPatternOptionFactory EXTENSION_OPTION = pattern ->
            new PathPatternOption(3,
                                  "extension",
                                  pattern,
                                  extension -> {
                                      checkArgument(isValidFileExtension(extension), "extension");
                                      if (extension.startsWith(".")) {
                                          return new DefaultPathPattern(
                                                  ImmutableSet.of("/**/*" + extension));
                                      } else { // need to add extension separator
                                          return new DefaultPathPattern(
                                                  ImmutableSet.of("/**/*." + extension));
                                      }
                                  });

    private static boolean isValidFileExtension(String extension) {
        checkArgument(!extension.isEmpty(), "extension");
        return FILE_EXTENSION_PATTERN.matcher(extension).matches();
    }
}
