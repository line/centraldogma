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

package com.linecorp.centraldogma.server.repository.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class TagUtilTest {

    @Test
    public void testByteHexDirName() throws Exception {
        assertEquals("01/", TagUtil.byteHexDirName(0x00000001));
        assertEquals("0a/0b/", TagUtil.byteHexDirName(0x00000b0a));
        assertEquals("0a/0b/0c/", TagUtil.byteHexDirName(0x000c0b0a));
        assertEquals("0a/0b/0c/0d/", TagUtil.byteHexDirName(0x0d0c0b0a));
    }

    @Test
    public void testByteHexDirNameException() {
        try {
            TagUtil.byteHexDirName(0);
            fail();
        } catch (IllegalArgumentException ignored) {
            // Expected
        }

        try {
            TagUtil.byteHexDirName(-1);
            fail();
        } catch (IllegalArgumentException ignored) {
            // Expected
        }
    }
}
