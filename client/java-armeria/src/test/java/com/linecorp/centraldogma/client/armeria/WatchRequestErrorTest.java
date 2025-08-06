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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class WatchRequestErrorTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            final CentralDogmaRepository repo = client.createRepository("foo", "bar").join();
            repo.commit("Test", Change.ofTextUpsert("/a.txt", "Hello, world!"))
                .push().join();
        }
    };

    @Test
    void shouldFailInitialValueFutureForInitialFailure() throws Exception {
        final CentralDogma client = dogma.client();
        final CentralDogmaRepository repo = client.forRepo("foo", "bar");
        final Entry<?> entry = repo.file("/a.txt").get().join();
        // Make sure the entry is available before testing the watcher.
        assertThat(entry.contentAsText().trim()).isEqualTo("Hello, world!");

        final Watcher<String> watcher = repo.watcher(PathPattern.of("/a.txt"))
                                            .<String>map(txt -> {
                                                throw new IllegalStateException("Test exception");
                                            })
                                            .start();
        assertThatThrownBy(() -> {
            watcher.initialValueFuture().get(15, TimeUnit.SECONDS);
        }).isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Test exception");
    }
}
