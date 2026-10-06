/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.RepositoryStatus;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;

class RepositoryDtoTest {

    @Test
    void alwaysSerializesEncryptedState() throws Exception {
        assertThat(Jackson.writeValueAsString(newRepository(false))).contains("\"encrypted\":false");
        assertThat(Jackson.writeValueAsString(newRepository(true))).contains("\"encrypted\":true");
        assertThat(Jackson.writeValueAsString(RepositoryDto.removed("repo")))
                .contains("\"encrypted\":false");
    }

    private static RepositoryDto newRepository(boolean encrypted) {
        return new RepositoryDto("project", "repo", Author.SYSTEM, Revision.INIT, 0,
                                 RepositoryStatus.ACTIVE, encrypted);
    }
}
