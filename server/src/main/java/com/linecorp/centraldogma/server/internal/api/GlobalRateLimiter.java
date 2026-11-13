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

import static com.linecorp.centraldogma.server.internal.api.RateLimitingUtil.counter;
import static com.linecorp.centraldogma.server.internal.api.RateLimitingUtil.newStrategy;
import static com.linecorp.centraldogma.server.internal.api.RateLimitingUtil.newThrottlingDecorator;

import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.throttling.ThrottlingStrategy;
import com.linecorp.centraldogma.server.RateLimitConfig;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Limits content push and revert requests using a single budget shared by all repositories on this server.
 */
public final class GlobalRateLimiter implements DecoratorFactoryFunction<GlobalRateLimited> {

    @Nullable
    private final ThrottlingStrategy<HttpRequest> strategy;

    public GlobalRateLimiter(@Nullable RateLimitConfig config, MeterRegistry meterRegistry) {
        strategy = config == null || config.requests() == -1 ? null : newStrategy(
                config.requests(), config.timeUnit(),
                counter(meterRegistry, "global", "", "passed"),
                counter(meterRegistry, "global", "", "rejected"));
    }

    @Override
    public Function<? super HttpService, ? extends HttpService> newDecorator(GlobalRateLimited parameter) {
        if (strategy == null) {
            return Function.identity();
        }
        return newThrottlingDecorator(strategy, "Server content push rate limit exceeded.");
    }
}
