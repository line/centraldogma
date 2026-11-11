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

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.throttling.ThrottlingService;
import com.linecorp.armeria.server.throttling.ThrottlingStrategy;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.server.RateLimitingConfig;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Limits content push requests independently for each repository.
 */
public final class RepositoryRateLimiter implements DecoratorFactoryFunction<RateLimited> {

    private final List<Rule> rules;
    private final ProjectManager projectManager;

    // Keep budgets for the lifetime of the repositories, without retaining removed repositories.
    private final Cache<Repository, ThrottlingStrategy<HttpRequest>> strategies =
            Caffeine.newBuilder().weakKeys().build();

    public RepositoryRateLimiter(List<RateLimitingConfig> config, ProjectManager projectManager,
                                 MeterRegistry meterRegistry) {
        rules = config.stream().map(rule -> new Rule(rule, meterRegistry)).collect(toImmutableList());
        this.projectManager = projectManager;
    }

    @Override
    public Function<? super HttpService, ? extends HttpService> newDecorator(RateLimited parameter) {
        if (rules.isEmpty()) {
            return Function.identity();
        }
        final ThrottlingStrategy<HttpRequest> strategy = ThrottlingStrategy.of(this::accept);
        return ThrottlingService.builder(strategy)
                                .onRejectedRequest((delegate, ctx, req, cause) -> {
                                    if (cause != null) {
                                        return HttpApiUtil.newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR,
                                                                      cause);
                                    }
                                    return HttpApiUtil.newResponse(
                                            ctx, HttpStatus.TOO_MANY_REQUESTS,
                                            "Repository content push rate limit exceeded.");
                                })
                                .newDecorator();
    }

    private CompletionStage<Boolean> accept(ServiceRequestContext ctx, HttpRequest req) {
        final String projectName = ctx.pathParam("projectName");
        final String repoName = ctx.pathParam("repoName");
        if (projectName == null || repoName == null) {
            return UnmodifiableFuture.completedFuture(true);
        }
        final String projectAndRepoName = projectName + '/' + repoName;
        Rule matchedRule = null;
        for (Rule rule : rules) {
            if (rule.config.matches(projectAndRepoName)) {
                matchedRule = rule;
                break;
            }
        }
        if (matchedRule == null) {
            return UnmodifiableFuture.completedFuture(true);
        }

        final Repository repository;
        try {
            repository = projectManager.get(projectName).repos().get(repoName);
        } catch (ProjectNotFoundException | RepositoryNotFoundException ignored) {
            // Let the API handle nonexistent resources without allocating limiter state for arbitrary names.
            return UnmodifiableFuture.completedFuture(true);
        }
        final Rule rule = matchedRule;
        final double rate = rule.config.requestsPerSecond();
        final CompletionStage<Boolean> result =
                rate == 0 ? UnmodifiableFuture.completedFuture(true)
                          : strategies.get(repository, unused -> ThrottlingStrategy.rateLimiting(rate))
                                      .accept(ctx, req);
        return result.thenApply(passed -> {
            if (passed) {
                rule.passed.increment();
            } else {
                rule.rejected.increment();
            }
            return passed;
        });
    }

    private static final class Rule {

        final RateLimitingConfig config;
        final Counter passed;
        final Counter rejected;

        Rule(RateLimitingConfig config, MeterRegistry meterRegistry) {
            this.config = config;
            passed = meterRegistry.counter("content.push.rate.limiting.requests",
                                           "pattern", config.pattern(), "result", "passed");
            rejected = meterRegistry.counter("content.push.rate.limiting.requests",
                                             "pattern", config.pattern(), "result", "rejected");
        }
    }
}
