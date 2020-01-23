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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class TagUtilTest {

    @Test
    void testByteHexDirName() {
        assertThat(TagUtil.byteHexDirName(0x00000001)).isEqualTo("01/");
        assertThat(TagUtil.byteHexDirName(0x00000b0a)).isEqualTo("0a/0b/");
        assertThat(TagUtil.byteHexDirName(0x000c0b0a)).isEqualTo("0a/0b/0c/");
        assertThat(TagUtil.byteHexDirName(0x0d0c0b0a)).isEqualTo("0a/0b/0c/0d/");
    }

    @Test
    void testByteHexDirNameException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TagUtil.byteHexDirName(0));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> TagUtil.byteHexDirName(-1));
    }
}
