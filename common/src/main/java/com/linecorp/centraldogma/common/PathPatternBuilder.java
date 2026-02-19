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
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.internal.Util;

/**
 * Builds a new {@link PathPattern}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * final PathPattern pathPattern =
 *         PathPattern.builder()
 *                    .startsWith("/foo/bar")
 *                    .contains("/ext")
 *                    .hasExtension("json")
 *                    .build();
 * }</pre>
 */
public final class PathPatternBuilder {

    private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile("^\\.{0,1}[0-9a-zA-Z]+$");

    @Nullable
    private PathPattern startPattern;
    private final List<PathPattern> innerPatterns = new ArrayList<>();
    @Nullable
    private PathPattern endPattern;

    PathPatternBuilder() {}

    /**
     * Ensures the file name component matches the specified {@code filename}.
     * For example, `endWith("foo.txt")` will match `/foo.txt`, `/alice/foo.txt` and
     * `/alice/bob/foo.txt`, but not `/barfoo.txt`.
     *
     * <p>This option can only be specified once; multiple declarations will override one another.
     *
     * <p>Note: this option and {@link PathPatternBuilder#hasExtension(String)} are mutually exclusive.
     * When both are specified, the latter-most option will override the former.
     */
    public PathPatternBuilder endsWith(String filename) {
        checkArgument(Util.isValidFileName(filename), "filename");
        // "/**" is added by the constructor of `DefaultPathPattern`
        endPattern = new DefaultPathPattern(filename);
        return this;
    }

    /**
     * Ensures the file extension component matches the specified {@code extension}.
     * For example, `hasExtension("json")` will match `mux.json`, `/bar/mux.json` and
     * `/alice/bar/mux.json` but not `/json.txt`.
     *
     * <p>This option can only be specified once; multiple declarations will override one another.
     *
     * <p>Note: this option and {@link PathPatternBuilder#endsWith(String)} are mutually exclusive.
     *  When both are specified, the latter-most option will override the former.
     */
    public PathPatternBuilder hasExtension(String extension) {
        checkArgument(isValidFileExtension(extension), "invalid extension.");
        if (extension.startsWith(".")) {
            endPattern = new DefaultPathPattern("/**/*" + extension);
        } else { // add extension separator
            endPattern = new DefaultPathPattern("/**/*." + extension);
        }
        return this;
    }

    /**
     * Ensures the directory path starts with the specified {@code dirPath}.
     * For example, `startsWith("/foo")` will match `/foo/test.zip`, `/foo/bar/test.zip`
     * but not `/nix/foo/test.zip`.
     *
     * <p>This option can only be specified once; multiple declarations will override one another.
     */
    public PathPatternBuilder startsWith(String dirPath) {
        checkArgument(Util.isValidDirPath(dirPath), "dir");
        // appends "/**"
        startPattern = new DefaultPathPattern(dirPath + (dirPath.endsWith("/") ? "" : "/") + "**");
        return this;
    }

    /**
     * Ensures the directory path contains the specified {@code dirPath}.
     * For example, `contains("/bar")` will match `/nix/bar/test.zip`, `/nix/quix/bar/twee/test.zip`
     * but not `/bar/foo/test.zip` or `/ren/bar.json`.
     *
     * <p>This option can be specified multiple times; multiple declarations will be chained.
     * For example, `contains("/bar").contains("foo")`
     * creates the glob-like pattern string `&#47;**&#47;bar&#47;**&#47;foo&#47;**".
     */
    public PathPatternBuilder contains(String dirPath) {
        checkArgument(Util.isValidDirPath(dirPath), "dirPath");
        // Prepends and appends "/**"
        final PathPattern contain = new DefaultPathPattern("/**" + dirPath +
                                                           (dirPath.endsWith("/") ? "" : "/") + "**");
        innerPatterns.add(contain);
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
        final ImmutableList.Builder<PathPattern> optionsBuilder = ImmutableList.builder();
        if (startPattern != null) {
            optionsBuilder.add(startPattern);
        }
        optionsBuilder.addAll(innerPatterns);
        if (endPattern != null) {
            optionsBuilder.add(endPattern);
        }
        final ImmutableList<PathPattern> options = optionsBuilder.build();

        checkState(!options.isEmpty(), "Requires at least one pattern to build in PathPatternBuilder");

        if (options.size() == 1) {
            return options.get(0);
        }
        return new DefaultPathPattern(combine(options));
    }

    private static boolean isValidFileExtension(String extension) {
        requireNonNull(extension, "extension");
        checkArgument(!extension.isEmpty(), "extension is empty.");
        return FILE_EXTENSION_PATTERN.matcher(extension).matches();
    }
}
