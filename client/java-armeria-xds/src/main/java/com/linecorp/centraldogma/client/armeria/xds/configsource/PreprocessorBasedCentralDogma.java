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
package com.linecorp.centraldogma.client.armeria.xds.configsource;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.internal.client.armeria.ArmeriaCentralDogma;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Creates a {@link CentralDogma} client that connects through an {@link HttpPreprocessor}.
 */
final class PreprocessorBasedCentralDogma {

    static CentralDogma of(HttpPreprocessor preprocessor, String accessToken,
                           MeterRegistry meterRegistry) {
        requireNonNull(preprocessor, "preprocessor");
        requireNonNull(accessToken, "accessToken");
        final ClientBuilder builder =
                Clients.builder(ClientPreprocessors.of(preprocessor));
        builder.decorator(DecodingClient.newDecorator());
        final WebClient client = builder.build(WebClient.class);
        return new ArmeriaCentralDogma(CommonPools.blockingTaskExecutor(), client, accessToken,
                                       () -> {}, meterRegistry, null);
    }

    private PreprocessorBasedCentralDogma() {}
}
