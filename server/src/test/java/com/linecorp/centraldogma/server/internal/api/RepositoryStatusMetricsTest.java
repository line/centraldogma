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

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Verifies that the {@code repository.read.only} metric is refreshed through the
 * {@link com.linecorp.centraldogma.server.command.StandaloneCommandExecutor} hooks as a read-only
 * repository is soft-removed, restored and purged. Unlike {@code RepoStatusManagerTest}, this drives
 * the real command-executor path so the hooks themselves are exercised (not simulated).
 */
class RepositoryStatusMetricsTest {

    static final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.meterRegistry(meterRegistry);
        }

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        }
    };

    @Test
    void readOnlyScopeMetricFollowsRepositoryLifecycle() {
        final BlockingWebClient client = dogma.httpClient().blocking();
        dogma.client().createProject("mp").join();
        dogma.client().createRepository("mp", "mr").join();
        setReadOnly(client, "mp", "mr");

        // Marked read-only -> a metric row exists.
        await().untilAsserted(() -> assertThat(readOnlyGauge("mp", "mr")).isNotNull());

        // Soft-remove -> the removeRepository hook must drop the metric row.
        dogma.client().removeRepository("mp", "mr").join();
        await().untilAsserted(() -> assertThat(readOnlyGauge("mp", "mr")).isNull());

        // Restore -> the unremoveRepository hook must bring it back.
        dogma.client().unremoveRepository("mp", "mr").join();
        await().untilAsserted(() -> assertThat(readOnlyGauge("mp", "mr")).isNotNull());

        // Remove + purge -> the purgeRepository hook must delete it permanently.
        dogma.client().removeRepository("mp", "mr").join();
        dogma.client().purgeRepository("mp", "mr").join();
        await().untilAsserted(() -> assertThat(readOnlyGauge("mp", "mr")).isNull());
    }

    @Test
    void purgingANonRemovedRepositoryKeepsItsReadOnlyStatus() {
        final BlockingWebClient client = dogma.httpClient().blocking();
        dogma.client().createProject("kp").join();
        dogma.client().createRepository("kp", "kr").join();
        setReadOnly(client, "kp", "kr");
        await().untilAsserted(() -> assertThat(readOnlyGauge("kp", "kr")).isNotNull());

        // Purge without removing first: markForPurge() is a no-op, so the read-only status must be
        // kept. Deleting it here would silently defeat read-only enforcement on a still-active repo.
        try {
            dogma.client().purgeRepository("kp", "kr").join();
        } catch (Exception e) {
            // Purging a non-removed repository may be rejected at the metadata layer; the only thing
            // under test here is that the read-only status was not deleted by the command.
        }
        // The read-only status (and hence its metric row) is still there.
        assertThat(readOnlyGauge("kp", "kr")).isNotNull();
    }

    private static void setReadOnly(BlockingWebClient client, String project, String repo) {
        client.prepare()
              .put(API_V1_PATH_PREFIX + "projects/" + project + "/repos/" + repo + "/status")
              .contentJson(new UpdateRepositoryStatusRequest(ReplicationStatus.READ_ONLY))
              .execute();
    }

    private static Gauge readOnlyGauge(String project, String repo) {
        return meterRegistry.find("repository.read.only")
                            .tags("project", project, "repo", repo)
                            .gauge();
    }
}
