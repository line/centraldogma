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
import static com.linecorp.centraldogma.common.DefaultPathPattern.ALL;
import static com.linecorp.centraldogma.common.DefaultPathPattern.allPattern;
import static com.linecorp.centraldogma.common.DefaultPathPattern.normalizeExtension;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;

/**
 * A path pattern that represents a variant of glob. For example:
 * <ul>
 *   <li>{@code "/**"} - all files</li>
 *   <li>{@code "*.json"} - all JSON files</li>
 *   <li>{@code "/foo/*.json"} - all JSON files under the directory {@code /foo}</li>
 *   <li><code>"/&#42;/foo.txt"</code> - all files named {@code foo.txt} at the second depth level</li>
 *   <li>{@code "*.json","/bar/*.txt"} - if you have more than one pattern you can supply them as
 *                                       {@code varargs} or {@link Iterable}.
 *                                       A file will be matched if <em>any</em> pattern matches.</li>
 * </ul>
 */
public interface PathPattern {

    /**
     * Returns the path pattern that represents all files.
     */
    static PathPattern all() {
        return allPattern;
    }

    /**
     * Creates a path pattern with the {@code patterns}.
     */
    static PathPattern of(String... patterns) {
        return of(ImmutableSet.copyOf(requireNonNull(patterns, "patterns")));
    }

    /**
     * Creates a path pattern with the {@code patterns}.
     */
    static PathPattern of(Iterable<String> patterns) {
        requireNonNull(patterns, "patterns");
        if (Streams.stream(patterns).anyMatch(ALL::equals)) {
            return allPattern;
        }

        return new DefaultPathPattern(ImmutableSet.copyOf(patterns));
    }

    /**
     * Creates a path pattern that matches the files whose extension is the specified {@code extension}.
     * A leading dot in {@code extension} is optional and is added automatically if missing.
     * The {@code extension} must consist of alphanumeric characters only.
     * For example, {@code PathPattern.ofExtension("json")} matches all JSON files at any depth,
     * which is equivalent to <code>PathPattern.of("/&#42;&#42;/*.json")</code>.
     */
    static PathPattern ofExtension(String extension) {
        requireNonNull(extension, "extension");
        return of("/**/*." + normalizeExtension(extension));
    }

    /**
     * Creates a path pattern that matches the files whose path starts with the specified {@code prefix}.
     * The {@code prefix} is anchored at the root, so a leading slash is added automatically if missing.
     * The match is not restricted to complete path segments; for example,
     * {@code PathPattern.startsWith("/foo/ba")} matches both {@code /foo/bar/a.txt} and {@code /foo/baz.txt},
     * which is equivalent to <code>PathPattern.of("/foo/ba&#42;&#42;")</code>.
     * The {@code prefix} must not contain a wildcard character ({@code '*'}).
     * Use {@link #under(String)} to match only the files under a directory.
     */
    static PathPattern startsWith(String prefix) {
        requireNonNull(prefix, "prefix");
        checkArgument(!prefix.isEmpty(), "prefix is empty.");
        checkArgument(prefix.indexOf('*') < 0, "prefix: %s (must not contain '*')", prefix);
        final String normalized = prefix.startsWith("/") ? prefix : '/' + prefix;
        return of(normalized + "**");
    }

    /**
     * Creates a path pattern that matches the files under the specified {@code directory}.
     * The {@code directory} is anchored at the root, so a leading slash is added automatically if missing,
     * and a trailing slash is optional. Unlike {@link #startsWith(String)}, the match is restricted to
     * complete path segments; for example, {@code PathPattern.under("/foo/bar")} matches {@code /foo/bar/a.txt}
     * but not {@code /foo/bar-baz.txt}, which is equivalent to
     * <code>PathPattern.of("/foo/bar/&#42;&#42;")</code>.
     * The {@code directory} must not contain a wildcard character ({@code '*'}).
     */
    static PathPattern under(String directory) {
        requireNonNull(directory, "directory");
        checkArgument(!directory.isEmpty(), "directory is empty.");
        checkArgument(directory.indexOf('*') < 0, "directory: %s (must not contain '*')", directory);
        String dir = directory;
        if (!dir.startsWith("/")) {
            dir = '/' + dir;
        }
        if (dir.endsWith("/")) {
            return of(dir + "**");
        }
        return of(dir + "/**");
    }

    /**
     * Returns the path pattern that concatenates the {@code patterns} using ','.
     */
    String patternString();

    /**
     * Returns the encoded {@link #patternString()} which just encodes a space to '%20'.
     */
    String encoded();
}
