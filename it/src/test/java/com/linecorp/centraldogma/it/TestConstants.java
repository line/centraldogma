/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.centraldogma.it;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class TestConstants {

    private static final Set<String> previousRandomTexts = new HashSet<>();

    public static String randomText() {
        for (;;) {
            long v = Math.abs(ThreadLocalRandom.current().nextLong());
            if (v < 0) { // Long.MIN_VALUE
                v = Long.MAX_VALUE;
            }

            final String text = Long.toString(v, Character.MAX_RADIX);
            if (previousRandomTexts.add(text)) {
                return text;
            }
        }
    }

    private TestConstants() {}
}
