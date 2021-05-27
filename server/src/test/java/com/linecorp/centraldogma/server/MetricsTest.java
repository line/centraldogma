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
package com.linecorp.centraldogma.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;

class MetricsTest {

    private static final Logger logger = LoggerFactory.getLogger(MetricsTest.class);

    @RegisterExtension
    static CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
            client.push("foo", "bar", Revision.HEAD, "Initial file",
                        Change.ofJsonUpsert("/foo.json", "{ \"a\": \"bar\" }")).join();
        }
    };

    @Test
    void metrics() {
        assertThat(dogma.dogma().meterRegistry()).containsInstanceOf(PrometheusMeterRegistry.class);

        AggregatedHttpResponse res = dogma.httpClient().get("/monitor/metrics").aggregate().join();
        String content = res.contentUtf8();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.parse(TextFormat.CONTENT_TYPE_004));
        assertThat(content).isNotEmpty();
        assertThat(content).doesNotContain(
                "com.linecorp.centraldogma.server.internal.api.WatchContentServiceV1");

        dogma.client().watchFile("foo", "bar", Revision.HEAD, Query.ofJson("/foo.json"), 100).join();
        res = dogma.httpClient().get("/monitor/metrics").aggregate().join();
        content = res.contentUtf8();
        assertThat(content).contains("com.linecorp.centraldogma.server.internal.api.WatchContentServiceV1");
    }
}
