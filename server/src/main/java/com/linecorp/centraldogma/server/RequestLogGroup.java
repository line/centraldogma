/*
 * Copyright 2022 LINE Corporation
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

import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants;

/**
 * A target service group which requests are logged.
 */
public enum RequestLogGroup {
    /**
     * The services served under {@code "/api"}.
     */
    API,
    /**
     * The {@link PrometheusExpositionService} served at {@value HttpApiV1Constants#METRICS_PATH}.
     */
    METRICS,
    /**
     * The {@link HealthCheckService} served at {@value HttpApiV1Constants#HEALTH_CHECK_PATH}.
     */
    HEALTH,
    /**
     * The {@link DocService} served under {@value HttpApiV1Constants#DOCS_PATH}.
     */
    DOCS,
    /**
     * The static file services served under {@code "/web"}, {@code "/vendor"}, {@code "/scripts"} and
     * {@code "/styles"}.
     */
    WEB,
    /**
     * The group that represents all {@link RequestLogGroup}s.
     */
    ALL;
}
