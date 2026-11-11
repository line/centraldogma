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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

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

/**
 * Limits content push requests independently for each repository.
 */
public final class RepositoryRateLimiter implements DecoratorFactoryFunction<RateLimited> {

    @Nullable
    private final RateLimitingConfig config;
    private final ProjectManager projectManager;

    // Keep budgets for the lifetime of the repositories, without retaining removed repositories.
    private final Cache<Repository, ThrottlingStrategy<HttpRequest>> strategies =
            Caffeine.newBuilder().weakKeys().build();

    public RepositoryRateLimiter(@Nullable RateLimitingConfig config, ProjectManager projectManager) {
        this.config = config;
        this.projectManager = requireNonNull(projectManager, "projectManager");
    }

    @Override
    public Function<? super HttpService, ? extends HttpService> newDecorator(RateLimited parameter) {
        if (config == null) {
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
        final RateLimitingConfig config = this.config;
        assert config != null;
        final String projectName = ctx.pathParam("projectName");
        final String repoName = ctx.pathParam("repoName");
        if (projectName == null || repoName == null) {
            return UnmodifiableFuture.completedFuture(true);
        }
        final double rate = config.requestsPerSecond(projectName, repoName);
        if (rate == 0) {
            return UnmodifiableFuture.completedFuture(true);
        }

        final Repository repository;
        try {
            repository = projectManager.get(projectName).repos().get(repoName);
        } catch (ProjectNotFoundException | RepositoryNotFoundException ignored) {
            // Let the API handle nonexistent resources without allocating limiter state for arbitrary names.
            return UnmodifiableFuture.completedFuture(true);
        }
        return strategies.get(repository, unused -> ThrottlingStrategy.rateLimiting(rate)).accept(ctx, req);
    }
}
