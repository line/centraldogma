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
package com.linecorp.centraldogma.internal.api.v1;

import static com.linecorp.centraldogma.internal.api.v1.WatchTimeout.MAX_MILLIS;
import static com.linecorp.centraldogma.internal.api.v1.WatchTimeout.makeReasonable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class WatchTimeoutTest {

    @Test
    public void testMakeReasonable() {
        assertThat(makeReasonable(1_000, 1_000)).isEqualTo(2_000);
        assertThat(makeReasonable(MAX_MILLIS, 1_000)).isEqualTo(MAX_MILLIS);
        assertThat(makeReasonable(MAX_MILLIS + 1_000, 0)).isEqualTo(MAX_MILLIS);
        assertThat(makeReasonable(MAX_MILLIS - 1_000, 500)).isEqualTo(MAX_MILLIS - 500);

        assertThatThrownBy(() -> makeReasonable(0, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> makeReasonable(-1, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> makeReasonable(1_000, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
