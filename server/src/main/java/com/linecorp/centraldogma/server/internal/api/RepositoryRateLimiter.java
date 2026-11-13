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
import static com.linecorp.centraldogma.server.internal.api.RateLimitingUtil.counter;
import static com.linecorp.centraldogma.server.internal.api.RateLimitingUtil.newThrottlingDecorator;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.throttling.ThrottlingStrategy;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.server.RateLimitRule;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Limits content push and revert requests independently for each repository.
 */
public final class RepositoryRateLimiter implements DecoratorFactoryFunction<RepositoryRateLimited> {

    private final List<Rule> rules;
    private final ProjectManager projectManager;

    // Keep budgets for the lifetime of the repositories, without retaining removed repositories.
    private final Cache<Repository, ThrottlingStrategy<HttpRequest>> strategies =
            Caffeine.newBuilder().weakKeys().build();

    public RepositoryRateLimiter(List<RateLimitRule> config, ProjectManager projectManager,
                                 MeterRegistry meterRegistry) {
        rules = config.stream().map(rule -> new Rule(rule, meterRegistry)).collect(toImmutableList());
        this.projectManager = projectManager;
    }

    @Override
    public Function<? super HttpService, ? extends HttpService> newDecorator(RepositoryRateLimited parameter) {
        if (rules.isEmpty()) {
            return Function.identity();
        }
        return newThrottlingDecorator(ThrottlingStrategy.of(this::accept),
                                      "Repository content push rate limit exceeded.");
    }

    private CompletionStage<Boolean> accept(ServiceRequestContext ctx, HttpRequest req) {
        final String projectName = ctx.pathParam("projectName");
        final String repoName = ctx.pathParam("repoName");
        if (projectName == null || repoName == null) {
            return UnmodifiableFuture.completedFuture(true);
        }
        final String projectAndRepoName = projectName + '/' + repoName;
        for (Rule rule : rules) {
            if (rule.config.matches(projectAndRepoName)) {
                final Repository repository = findRepository(projectName, repoName);
                if (repository == null) {
                    return UnmodifiableFuture.completedFuture(true);
                }
                return strategies.get(repository, unused -> rule.newStrategy()).accept(ctx, req);
            }
        }
        return UnmodifiableFuture.completedFuture(true);
    }

    @Nullable
    private Repository findRepository(String projectName, String repoName) {
        try {
            return projectManager.get(projectName).repos().get(repoName);
        } catch (ProjectNotFoundException | RepositoryNotFoundException ignored) {
            // Let the API handle nonexistent resources without allocating limiter state for arbitrary names.
            return null;
        }
    }

    private static final class Rule {

        final RateLimitRule config;
        final Counter passed;
        final Counter rejected;

        Rule(RateLimitRule config, MeterRegistry meterRegistry) {
            this.config = config;
            final String pattern = requireNonNull(config.pattern(), "config.pattern");
            passed = counter(meterRegistry, "repository", pattern, "passed");
            rejected = counter(meterRegistry, "repository", pattern, "rejected");
        }

        ThrottlingStrategy<HttpRequest> newStrategy() {
            return RateLimitingUtil.newStrategy(config.requests(), config.timeUnit(), passed, rejected);
        }
    }
}
