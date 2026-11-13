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

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.throttling.ThrottlingService;
import com.linecorp.armeria.server.throttling.ThrottlingStrategy;
import com.linecorp.armeria.server.throttling.bucket4j.TokenBucket;
import com.linecorp.armeria.server.throttling.bucket4j.TokenBucketThrottlingStrategy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class RateLimitingUtil {

    static Function<? super HttpService, ? extends HttpService> newThrottlingDecorator(
            ThrottlingStrategy<HttpRequest> strategy, String rejectionMessage) {
        return ThrottlingService.builder(strategy)
                                .onRejectedRequest((delegate, ctx, req, cause) -> {
                                    if (cause != null) {
                                        return HttpApiUtil.newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR,
                                                                      cause);
                                    }
                                    return HttpApiUtil.newResponse(ctx, HttpStatus.TOO_MANY_REQUESTS,
                                                                  rejectionMessage);
                                })
                                .newDecorator();
    }

    static ThrottlingStrategy<HttpRequest> newStrategy(
            long requests, TimeUnit timeUnit, Counter passed, Counter rejected) {
        final ThrottlingStrategy<HttpRequest> delegate;
        if (requests == -1) {
            delegate = ThrottlingStrategy.of((ctx, req) -> UnmodifiableFuture.completedFuture(true));
        } else if (requests == 0) {
            delegate = ThrottlingStrategy.of((ctx, req) -> UnmodifiableFuture.completedFuture(false));
        } else {
            final TokenBucket bucket = TokenBucket.of(requests, Duration.ofNanos(timeUnit.toNanos(1)));
            delegate = TokenBucketThrottlingStrategy.<HttpRequest>builder(bucket)
                                                   .minimumBackoff(Duration.ofSeconds(1))
                                                   .build();
        }
        return ThrottlingStrategy.of((ctx, req) -> delegate.accept(ctx, req).thenApply(accepted -> {
            if (accepted) {
                passed.increment();
            } else {
                rejected.increment();
            }
            return accepted;
        }));
    }

    static Counter counter(MeterRegistry registry, String scope, String pattern, String result) {
        return registry.counter("content.push.rate.limiting.requests",
                                "scope", scope, "pattern", pattern, "result", result);
    }

    private RateLimitingUtil() {}
}
