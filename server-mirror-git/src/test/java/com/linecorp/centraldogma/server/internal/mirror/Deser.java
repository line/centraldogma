/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.mirror;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;

class Deser {
    @Test
    void test() throws JsonProcessingException {
        String content = "{\"pushedAt\":\"2023-11-16T11:54:18.666Z\",\"revision\":2}";
        final PushResultDto pushResultDto = JacksonUtil.newDefaultObjectMapper().readValue(content,
                                                                                           PushResultDto.class);
        System.out.println(pushResultDto);
    }
}
