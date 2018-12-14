/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.centraldogma.internal.jsonpatch;

import static java.util.Objects.requireNonNull;

/**
 * A utility class for JSON patch operation.
 */
public final class JsonPatchUtil {

    /**
     * Adds a leading slash and replaces '/' and '~' with '~1' and '~0' respectively.
     * See <a href="https://tools.ietf.org/html/rfc6901">rfc6901</a> for more information.
     */
    public static String encodeSegment(String segment) {
        requireNonNull(segment, "segment");
        final StringBuilder sb = new StringBuilder(segment.length() + 1);
        sb.append('/');
        for (int i = 0, end = segment.length(); i < end; ++i) {
            char c = segment.charAt(i);
            if (c == '/') {
                sb.append("~1");
                continue;
            }
            if (c == '~') {
                sb.append("~0");
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private JsonPatchUtil() {}
}
