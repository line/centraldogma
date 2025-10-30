/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.server.mirror;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility class for creating a mirroring task.
 */
public final class MirrorUtil {

    private static final Pattern MIRROR_ID_PATTERN =
            Pattern.compile("^[a-zA-Z](?:[a-zA-Z0-9-_.]{0,61}[a-zA-Z0-9])?$");

    /**
     * Validates the specified {@code id} as a mirror ID.
     */
    public static void validateMirrorId(String id) {
        final Matcher matcher = MIRROR_ID_PATTERN.matcher(id);
        checkArgument(matcher.matches(),
                      "invalid mirror ID: %s (expected: %s)",
                      id, MIRROR_ID_PATTERN.pattern());
    }

    /**
     * Normalizes the specified {@code path}. A path which starts and ends with {@code /} would be returned.
     * Also, it would not have consecutive {@code /}.
     */
    public static String normalizePath(String path) {
        requireNonNull(path, "path");
        if (path.isEmpty()) {
            return "/";
        }

        if (!path.startsWith("/")) {
            path = '/' + path;
        }

        if (!path.endsWith("/")) {
            path += '/';
        }

        return path.replaceAll("//+", "/");
    }

    private MirrorUtil() {}
}
