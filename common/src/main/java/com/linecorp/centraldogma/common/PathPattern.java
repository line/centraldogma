package com.linecorp.centraldogma.common;

import static com.linecorp.centraldogma.common.DefaultPathPattern.allPattern;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableSet;

/**
 * A path pattern that represents a variant of glob. For example:
 * <ul>
 *   <li>{@code "/**"} - all files</li>
 *   <li>{@code "*.json"} - all JSON files</li>
 *   <li>{@code "/foo/*.json"} - all JSON files under the directory {@code /foo}</li>
 *   <li><code>"/&#42;/foo.txt"</code> - all files named {@code foo.txt} at the second depth level</li>
 *   <li>{@code "*.json,/bar/*.txt"} - use comma to specify more than one pattern. A file will be matched
 *                                     if <em>any</em> pattern matches.</li>
 * </ul>
 */
public interface PathPattern {

    static PathPattern all() {
        return allPattern;
    }

    static PathPattern of(String... patterns) {
        return of(ImmutableSet.copyOf(requireNonNull(patterns, "patterns")));
    }

    static PathPattern of(Iterable<String> patterns) {
        return new DefaultPathPattern(ImmutableSet.copyOf(requireNonNull(patterns, "patterns")));
    }

    String get();

    String encoded();
}
