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

import java.util.function.Function;
import java.util.regex.Pattern;

import com.linecorp.centraldogma.internal.Util;

/**
 * A set of {@link PathPatternOption}s.
 */
final class PathPatternOptions {

    private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile("^\\.{0,1}[0-9a-zA-Z]+$");

    private PathPatternOptions() {}

    /**
     * Appends "&#47;**" to {@code dirPath}.
     * Returns the path pattern for matching all file(s) under {@code dirPath}.
     */
    static final Function<String, PathPatternOption> STARTS_WITH = pattern ->
            new PathPatternOption(pattern,
                                  dirPath -> {
                                      checkArgument(Util.isValidDirPath(dirPath), "dir");
                                      return new DefaultPathPattern(
                                              dirPath + (dirPath.endsWith("/") ? "" : "/") + "**");
                                  });

    /**
     * Prepends and appends "&#47;**" to target {@code dirPath}.
     * Returns the path pattern for matching all file(s) containing {@code dirPath}.
     */
    static final Function<String, PathPatternOption> CONTAINS = pattern ->
            new PathPatternOption(pattern,
                                  dirPath -> {
                                      checkArgument(Util.isValidDirPath(dirPath), "dirPath");
                                      if (dirPath.endsWith("/")) {
                                          return new DefaultPathPattern("/**" + dirPath + "**");
                                      } else { // add ending slash to dirPath
                                          return new DefaultPathPattern("/**" + dirPath + "/**");
                                      }
                                  });

    /**
     * Prepends "&#47;**&#47;" to {@code filename}.
     * Returns the path pattern for matching file(s) ending in {@code filename}.
     */
    static final Function<String, PathPatternOption> ENDS_WITH = pattern ->
            new PathPatternOption(pattern,
                                  filename -> {
                                      checkArgument(Util.isValidFileName(filename), "filename");
                                      // `/**` is added by the constructor of `DefaultPathPattern`
                                      return new DefaultPathPattern(filename);
                                  });

    /**
     * Prepends "&#47;**&#47;*" to {@code extension}.
     * Returns the path pattern for matching file(s) ending in {@code extension}.
     */
    static final Function<String, PathPatternOption> EXTENSION = pattern ->
            new PathPatternOption(pattern,
                                  extension -> {
                                      checkArgument(isValidFileExtension(extension), "extension");
                                      if (extension.startsWith(".")) {
                                          return new DefaultPathPattern("/**/*" + extension);
                                      } else { // add extension separator
                                          return new DefaultPathPattern("/**/*." + extension);
                                      }
                                  });

    private static boolean isValidFileExtension(String extension) {
        checkArgument(!extension.isEmpty(), "extension");
        return FILE_EXTENSION_PATTERN.matcher(extension).matches();
    }
}
