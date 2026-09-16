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

package com.linecorp.centraldogma.server.internal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.RecoverRepositoryCommand;

class RecoverRepositoryRequestTest {

    @Test
    void rejectsJsonWithoutMaxRevision() {
        assertThatThrownBy(() -> Jackson.readValue(
                "{\"fromRevision\":2,\"toRevision\":3,\"sourceServerId\":1}",
                RecoverRepositoryRequest.class))
                .hasMessageContaining("maxRevision");
    }

    @Test
    void validatesCombinedReplayAndPaddingRevisions() {
        new RecoverRepositoryRequest(2, 3,
                                     2 + RecoverRepositoryCommand.MAX_RECOVERY_COMMITS - 2, 1);

        assertThatThrownBy(() -> new RecoverRepositoryRequest(
                2, 3, 2 + RecoverRepositoryCommand.MAX_RECOVERY_COMMITS - 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recovery");
    }

    @Test
    void keepsTheJsonCreatorArgumentOrder() {
        final RecoverRepositoryRequest request = new RecoverRepositoryRequest(2, 3, 4, 1);

        assertThat(request.fromRevision()).isEqualTo(2);
        assertThat(request.toRevision()).isEqualTo(3);
        assertThat(request.maxRevision()).isEqualTo(4);
        assertThat(request.sourceServerId()).isEqualTo(1);
    }
}
