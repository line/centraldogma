/*
 * Copyright 2019 LINE Corporation
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

import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.centraldogma.testing.junit4.CentralDogmaRule;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;

public class MetricsTest {

    private static final Logger logger = LoggerFactory.getLogger(MetricsTest.class);

    @ClassRule
    public static CentralDogmaRule rule = new CentralDogmaRule();

    @Test
    public void metrics() {
        assertThat(rule.dogma().meterRegistry()).containsInstanceOf(PrometheusMeterRegistry.class);

        final AggregatedHttpResponse res = rule.httpClient().get("/monitor/metrics").aggregate().join();
        logger.debug("Prometheus metrics:\n{}", res.contentUtf8());

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.parse(TextFormat.CONTENT_TYPE_004));
        assertThat(res.contentUtf8()).isNotEmpty();
    }
}
