/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.api.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

class PushMetricTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void shouldIncreasePushCounter() {
        final String projectName = "myPro";
        final String repoName = "myRepo";
        final MeterRegistry meterRegistry = dogma.dogma().meterRegistry().get();
        final Counter pushCounter = meterRegistry.find("commits.push")
                                                 .tags("project", projectName, "repository", repoName)
                                                 .counter();
        final double before = pushCounter != null ? pushCounter.count() : 0;
        final CentralDogma client = dogma.client();
        client.createProject(projectName).join();
        client.createRepository(projectName, repoName).join();
        client.forRepo(projectName, repoName)
              .commit("Add a file", Change.ofTextUpsert("/a.txt", "a"))
              .push().join();

        await().untilAsserted(() -> {
            final Counter pushCounter0 = meterRegistry.find("commits.push")
                                                      .tags("project", projectName, "repository", repoName)
                                                      .counter();
            assertThat(pushCounter0).isNotNull();
            // Check whether the push counter is increased by one.
            assertThat(pushCounter0.count()).isEqualTo(before + 1);
        });
    }
}
