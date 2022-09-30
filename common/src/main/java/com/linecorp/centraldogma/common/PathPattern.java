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

import static com.linecorp.centraldogma.common.DefaultPathPattern.ALL;
import static com.linecorp.centraldogma.common.DefaultPathPattern.allPattern;
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
 *   <li>{@code "*.json","/bar/*.txt"} - if you have more than 1 pattern you can supply them as {@code varargs} or {@link Iterable}. 
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
     * Returns the path pattern that concatenates the {@code patterns} using ','.
     */
    String patternString();

    /**
     * Returns the encoded {@link #patternString()} which just encodes a space to '%20'.
     */
    String encoded();
}
