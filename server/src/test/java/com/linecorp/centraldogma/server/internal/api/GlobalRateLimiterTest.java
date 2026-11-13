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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RoutingResult;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.server.RateLimitRule;
import com.linecorp.centraldogma.server.RateLimitingConfig;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GlobalRateLimiterTest {

    private final ProjectManager projectManager = mock(ProjectManager.class, RETURNS_DEEP_STUBS);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void repositoryRejectionsDoNotConsumeGlobalTokens() throws Exception {
        final HttpService service = service(new RateLimitingConfig(
                new RateLimitRule(2L, TimeUnit.DAYS),
                ImmutableList.of(new RateLimitRule(".*", 1L, TimeUnit.HOURS))));
        assertThat(push(service, "default", "one").status()).isEqualTo(HttpStatus.OK);
        assertRepositoryRejection(push(service, "default", "one"));
        assertRepositoryRejection(push(service, "default", "one"));
        assertThat(push(service, "other", "one").status()).isEqualTo(HttpStatus.OK);
        assertGlobalRejection(push(service, "default", "two"));
        assertThat(count("global", "", "passed")).isEqualTo(2);
        assertThat(count("global", "", "rejected")).isEqualTo(1);
        assertThat(count("repository", ".*", "passed")).isEqualTo(3);
        assertThat(count("repository", ".*", "rejected")).isEqualTo(2);
    }

    @Test
    void globalRejectionRetainsRepositoryToken() throws Exception {
        final HttpService service = service(new RateLimitingConfig(
                new RateLimitRule(1L, TimeUnit.DAYS),
                ImmutableList.of(new RateLimitRule(".*", 2L, TimeUnit.HOURS))));
        assertThat(push(service, "default", "one").status()).isEqualTo(HttpStatus.OK);
        assertGlobalRejection(push(service, "default", "one"));
        assertRepositoryRejection(push(service, "default", "one"));
        assertThat(count("global", "", "rejected")).isEqualTo(1);
        assertThat(count("repository", ".*", "passed")).isEqualTo(2);
        assertThat(count("repository", ".*", "rejected")).isEqualTo(1);
    }

    @Test
    void unmatchedAndUnlimitedRepositoriesStillUseGlobalBudget() throws Exception {
        final HttpService service = service(new RateLimitingConfig(
                new RateLimitRule(2L, TimeUnit.HOURS),
                ImmutableList.of(new RateLimitRule("default/unlimited", -1L, TimeUnit.MINUTES))));
        assertThat(push(service, "default", "unmatched").status()).isEqualTo(HttpStatus.OK);
        assertThat(push(service, "default", "unlimited").status()).isEqualTo(HttpStatus.OK);
        assertGlobalRejection(push(service, "default", "unlimited"));
        assertGlobalRejection(push(service, "other", "unmatched"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void globalLimitIsOptional(boolean explicitlyDisabled) throws Exception {
        final HttpService service = service(new RateLimitingConfig(
                explicitlyDisabled ? new RateLimitRule(-1L, TimeUnit.DAYS) : null,
                ImmutableList.of(new RateLimitRule("default/one", 1L, TimeUnit.HOURS))));
        assertThat(push(service, "default", "one").status()).isEqualTo(HttpStatus.OK);
        assertRepositoryRejection(push(service, "default", "one"));
        for (int i = 0; i < 5; i++) {
            assertThat(push(service, "default", "unmatched").status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void zeroRepositoryLimitRejectsAllRequestsWithoutConsumingGlobalTokens() throws Exception {
        final HttpService service = service(new RateLimitingConfig(
                new RateLimitRule(1L, TimeUnit.DAYS),
                ImmutableList.of(new RateLimitRule("default/blocked", 0L, TimeUnit.MINUTES),
                                 new RateLimitRule(".*", -1L, TimeUnit.DAYS))));
        final AggregatedHttpResponse rejected = push(service, "default", "blocked");
        assertRepositoryRejection(rejected);
        assertThat(rejected.headers().get(HttpHeaderNames.RETRY_AFTER)).isNull();
        assertRepositoryRejection(request(service, "default", "blocked", "revert"));
        assertThat(count("repository", "default/blocked", "passed")).isZero();
        assertThat(count("repository", "default/blocked", "rejected")).isEqualTo(2);
        assertThat(count("global", "", "passed")).isZero();
        assertThat(count("global", "", "rejected")).isZero();
        assertThat(push(service, "default", "allowed").status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void zeroGlobalLimitRejectsAllRequestsIncludingUnlimitedRepositories() throws Exception {
        final HttpService service = service(new RateLimitingConfig(
                new RateLimitRule(0L, TimeUnit.DAYS),
                ImmutableList.of(new RateLimitRule(".*", -1L, TimeUnit.MINUTES))));
        final AggregatedHttpResponse rejected = push(service, "default", "one");
        assertGlobalRejection(rejected);
        assertThat(rejected.headers().get(HttpHeaderNames.RETRY_AFTER)).isNull();
        assertGlobalRejection(push(service, "other", "two"));
        assertGlobalRejection(request(service, "default", "one", "revert"));
        assertThat(count("global", "", "passed")).isZero();
        assertThat(count("global", "", "rejected")).isEqualTo(3);
        assertThat(count("repository", ".*", "passed")).isEqualTo(3);
        assertThat(count("repository", ".*", "rejected")).isZero();
    }

    @Test
    void globalOnlyLimitIsSharedByPushesAndReverts() throws Exception {
        final HttpService service = service(new RateLimitingConfig(
                new RateLimitRule(2L, TimeUnit.DAYS), null));
        assertThat(request(service, "default", "one", "contents").status()).isEqualTo(HttpStatus.OK);
        assertThat(request(service, "other", "two", "revert").status()).isEqualTo(HttpStatus.OK);
        assertGlobalRejection(request(service, "default", "three", "contents"));
        assertGlobalRejection(request(service, "other", "four", "revert"));
    }

    @Test
    void concurrentRequestsShareOneGlobalBucket() {
        final HttpService service = service(new RateLimitingConfig(
                new RateLimitRule(5L, TimeUnit.DAYS), null));
        final List<CompletableFuture<HttpStatus>> requests = IntStream.range(0, 40)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return push(service, "default", "repo" + i).status();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }))
                .collect(Collectors.toList());
        final List<HttpStatus> statuses = requests.stream().map(CompletableFuture::join)
                                                 .collect(Collectors.toList());
        assertThat(statuses).filteredOn(HttpStatus.OK::equals).hasSize(5);
        assertThat(statuses).filteredOn(HttpStatus.TOO_MANY_REQUESTS::equals).hasSize(35);
        assertThat(count("global", "", "passed")).isEqualTo(5);
        assertThat(count("global", "", "rejected")).isEqualTo(35);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void exhaustedBucketsRefill(boolean global) throws Exception {
        final HttpService service = service(new RateLimitingConfig(
                global ? new RateLimitRule(1L, TimeUnit.SECONDS) : null,
                global ? null : ImmutableList.of(
                        new RateLimitRule(".*", 1L, TimeUnit.SECONDS))));
        assertThat(push(service, "default", "one").status()).isEqualTo(HttpStatus.OK);
        assertThat(push(service, "default", "one").status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(push(service, "default", "one").status()).isEqualTo(HttpStatus.OK));
    }

    @ParameterizedTest
    @CsvSource({ "true, true", "true, false", "false, true", "false, false" })
    void missingResourcesConsumeGlobalTokens(boolean missingProject, boolean repositoryLimited)
            throws Exception {
        if (missingProject) {
            when(projectManager.get("missing")).thenThrow(new ProjectNotFoundException("missing"));
        } else {
            when(projectManager.get("missing").repos().get("missing"))
                    .thenThrow(new RepositoryNotFoundException("missing"));
        }
        final HttpService service = service(new RateLimitingConfig(
                new RateLimitRule(1L, TimeUnit.DAYS),
                repositoryLimited ? ImmutableList.of(new RateLimitRule(".*", 1L, TimeUnit.DAYS))
                                  : null));
        // Missing resources reach the delegate, but still consume a global token.
        assertThat(push(service, "missing", "missing").status()).isEqualTo(HttpStatus.OK);
        assertGlobalRejection(push(service, "default", "one"));
        assertGlobalRejection(push(service, "missing", "missing"));
        assertThat(count("global", "", "passed")).isEqualTo(1);
        assertThat(count("global", "", "rejected")).isEqualTo(2);
    }

    @Test
    void hourlyAndDailyRefillPeriodsAreApplied() throws Exception {
        final HttpService service = service(new RateLimitingConfig(
                new RateLimitRule(1L, TimeUnit.DAYS),
                ImmutableList.of(new RateLimitRule("default/one", 1L, TimeUnit.HOURS))));
        assertThat(push(service, "default", "one").status()).isEqualTo(HttpStatus.OK);
        final HttpRequest repositoryRequest = HttpRequest.of(HttpMethod.POST, "/");
        final ServiceRequestContext repositoryContext = context(repositoryRequest, "default", "one");
        service.serve(repositoryContext, repositoryRequest).aggregate().join();
        final String repositoryRetryAfter =
                repositoryContext.additionalResponseHeaders().get(HttpHeaderNames.RETRY_AFTER);
        assertThat(Long.parseLong(repositoryRetryAfter))
                .isBetween(3590L, 3600L);
        final HttpRequest globalRequest = HttpRequest.of(HttpMethod.POST, "/");
        final ServiceRequestContext globalContext = context(globalRequest, "default", "two");
        service.serve(globalContext, globalRequest).aggregate().join();
        assertThat(Long.parseLong(globalContext.additionalResponseHeaders().get(HttpHeaderNames.RETRY_AFTER)))
                .isBetween(86390L, 86400L);
    }

    private HttpService service(RateLimitingConfig config) {
        final HttpService delegate = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        final HttpService globalLimited = new GlobalRateLimiter(config.global(), meterRegistry)
                .newDecorator(mock(GlobalRateLimited.class)).apply(delegate);
        return new RepositoryRateLimiter(config.repositories(), projectManager, meterRegistry)
                .newDecorator(mock(RepositoryRateLimited.class)).apply(globalLimited);
    }

    private double count(String scope, String pattern, String result) {
        return meterRegistry.get("content.push.rate.limiting.requests")
                            .tags("scope", scope, "pattern", pattern, "result", result).counter().count();
    }

    private static AggregatedHttpResponse push(HttpService service, String project, String repo)
            throws Exception {
        return request(service, project, repo, "contents");
    }

    private static AggregatedHttpResponse request(HttpService service, String project, String repo,
                                                 String endpoint) throws Exception {
        final String path = "/api/v1/projects/" + project + "/repos/" + repo + '/' + endpoint;
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, path);
        return service.serve(context(req, project, repo), req).aggregate().join();
    }

    private static ServiceRequestContext context(HttpRequest req, String project, String repo) {
        return ServiceRequestContext.builder(req)
                                    .routingResult(RoutingResult.builder().path(req.path())
                                                                .decodedParam("projectName", project)
                                                                .decodedParam("repoName", repo).build())
                                    .build();
    }

    private static void assertRepositoryRejection(AggregatedHttpResponse response) {
        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.contentUtf8()).contains("Repository content push rate limit exceeded.");
    }

    private static void assertGlobalRejection(AggregatedHttpResponse response) {
        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.contentUtf8()).contains("Server content push rate limit exceeded.");
    }
}
