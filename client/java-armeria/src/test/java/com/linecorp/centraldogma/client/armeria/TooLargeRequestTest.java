/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.centraldogma.client.armeria;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.RequestTooLargeException;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class TooLargeRequestTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void shouldRejectLargeContent() {
        final CentralDogma client = dogma.client();
        client.createProject("foo").join();
        final CentralDogmaRepository repo = client.createRepository("foo", "bar").join();
        final String largeContent = Strings.repeat("a", 1024 * 1024 + 1); // 1MB + 1 byte
        // Expecting a RequestTooLargeException when trying to push a change with large content.
        assertThatThrownBy(() -> {
            repo.commit("Should fail", Change.ofTextUpsert("/a.txt", largeContent))
                .push()
                .join();
        }).isInstanceOf(CompletionException.class)
          .hasRootCauseInstanceOf(RequestTooLargeException.class);
    }
}
