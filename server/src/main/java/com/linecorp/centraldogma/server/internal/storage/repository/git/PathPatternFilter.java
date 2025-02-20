/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static com.linecorp.centraldogma.internal.Util.validatePathPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import com.linecorp.centraldogma.server.storage.repository.Repository;

public final class PathPatternFilter extends TreeFilter {

    private static final Pattern SPLIT = Pattern.compile("\\s*,\\s*");

    private static final ThreadLocal<LruMap<String, PathPatternFilter>> filterCache =
            LruMap.newThreadLocal(512);

    private static final ThreadLocal<LruMap<String, Pattern>> regexCache = LruMap.newThreadLocal(1024);

    public static PathPatternFilter of(String pathPattern) {
        final LruMap<String, PathPatternFilter> map = filterCache.get();
        PathPatternFilter f = map.get(pathPattern);
        if (f == null) {
            f = new PathPatternFilter(pathPattern);
            map.put(pathPattern, f);
        }

        return f;
    }

    private final Pattern[] pathPatterns;
    private final String pathPattern;

    private PathPatternFilter(String pathPattern) {
        validatePathPattern(pathPattern, "pathPattern");

        final String[] pathPatterns = SPLIT.split(pathPattern);
        final StringBuilder pathPatternBuf = new StringBuilder(pathPattern.length());
        final List<Pattern> compiledPathPatterns = new ArrayList<>(pathPatterns.length);
        boolean matchAll = false;
        for (String p: pathPatterns) {
            if (Repository.ALL_PATH.equals(p)) {
                matchAll = true;
                break;
            }

            if (p.isEmpty()) {
                continue;
            }

            final String normalized = normalize(p);
            compiledPathPatterns.add(compile(normalized));
            pathPatternBuf.append(normalized).append(',');
        }

        if (matchAll) {
            this.pathPatterns = null;
            this.pathPattern = "/**";
        } else {
            if (compiledPathPatterns.isEmpty()) {
                throw new IllegalArgumentException("pathPattern is empty.");
            }

            this.pathPatterns = compiledPathPatterns.toArray(new Pattern[compiledPathPatterns.size()]);
            this.pathPattern = pathPatternBuf.substring(0, pathPatternBuf.length() - 1);
        }
    }

    private static String normalize(String p) {
        final String normalized;
        if (p.charAt(0) != '/') {
            normalized = "/**/" + p;
        } else {
            normalized = p;
        }
        return normalized;
    }

    private static Pattern compile(final String pathPattern) {
        if (pathPattern.isEmpty()) {
            throw new IllegalArgumentException("contains an empty path pattern");
        }

        final Map<String, Pattern> map = regexCache.get();
        Pattern compiled = map.get(pathPattern);
        if (compiled == null) {
            compiled = compileUncached(pathPattern);
            map.put(pathPattern, compiled);
        }

        return compiled;
    }

    private static Pattern compileUncached(String pathPattern) {
        if (pathPattern.charAt(0) != '/') {
            pathPattern = "/**/" + pathPattern;
        }

        final int pathPatternLen = pathPattern.length();
        final StringBuilder buf = new StringBuilder(pathPatternLen).append('^');
        int asterisks = 0;
        char beforeAsterisk = '/';

        for (int i = 1; i < pathPatternLen; i++) { // Start from '1' to skip the first '/'.
            final char c = pathPattern.charAt(i);
            if (c == '*') {
                asterisks++;
                if (asterisks > 2) {
                    throw new IllegalArgumentException(
                            "contains a path pattern with invalid wildcard characters: " + pathPattern +
                            " (only * and ** are allowed)");
                }
                continue;
            }

            switch (asterisks) {
            case 1:
                // Handle '/*/' specially.
                if (beforeAsterisk == '/' && c == '/') {
                    buf.append("[^/]+");
                } else {
                    buf.append("[^/]*");
                }
                break;
            case 2:
                // Handle '/**/' specially.
                if (beforeAsterisk == '/' && c == '/') {
                    buf.append("(?:.+/)?");
                    asterisks = 0;
                    beforeAsterisk = c;
                    continue;
                }

                buf.append(".*");
                break;
            }

            asterisks = 0;
            beforeAsterisk = c;

            switch (c) {
            case '\\':
            case '.':
            case '^':
            case '$':
            case '?':
            case '+':
            case '{':
            case '}':
            case '[':
            case ']':
            case '(':
            case ')':
            case '|':
                buf.append('\\');
                buf.append(c);
                break;
            default:
                buf.append(c);
            }
        }

        // Handle the case where the pattern ends with asterisk(s).
        switch (asterisks) {
        case 1:
            if (beforeAsterisk == '/') {
                // '/*<END>'
                buf.append("[^/]+");
            } else {
                buf.append("[^/]*");
            }
            break;
        case 2:
            buf.append(".*");
            break;
        }

        return Pattern.compile(buf.append('$').toString());
    }

    @Override
    public boolean include(TreeWalk walker) {
        if (walker.isSubtree()) {
            return true;
        }

        return matches(walker);
    }

    public boolean matches(TreeWalk walker) {
        if (pathPatterns == null) {
            return true;
        }

        for (Pattern p: pathPatterns) {
            if (p.matcher(walker.getPathString()).matches()) {
                return true;
            }
        }

        return false;
    }

    public boolean matches(String path) {
        if (pathPatterns == null) {
            return true;
        }

        if (path.charAt(0) == '/') {
            // Strip the leading '/' since pathPatterns was compiled without it.
            path = path.substring(1);
        }

        for (Pattern p: pathPatterns) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }

        return false;
    }

    public boolean matchesAll() {
        return pathPatterns == null;
    }

    @Override
    public boolean shouldBeRecursive() {
        return true;
    }

    @Override
    @SuppressWarnings("CloneInNonCloneableClass")
    public PathPatternFilter clone() {
        return this;
    }

    @Override
    public int hashCode() {
        return pathPattern.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PathPatternFilter)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        return pathPattern.equals(((PathPatternFilter) obj).pathPattern);
    }

    @Override
    public String toString() {
        return pathPattern;
    }
}
