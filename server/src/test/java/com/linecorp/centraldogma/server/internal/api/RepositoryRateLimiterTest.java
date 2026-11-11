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
import static org.eclipse.jgit.transport.GitProtocolConstants.VERSION_2_REQUEST;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.RateLimitingConfig;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RepositoryRateLimiterTest {

    // Each limiter admits its first request immediately, then takes 1000 seconds to replenish a permit.
    private static final double RATE = 0.001;
    private static final String METRIC_PATTERN = "metrics/(one|two)";
    private static final String FALLBACK_PATTERN = "(default|other|api|repoOverride)/.*";
    private static final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.webAppEnabled(true);
            builder.meterRegistry(meterRegistry);
            builder.rateLimiting(
                    new RateLimitingConfig(METRIC_PATTERN, RATE),
                    new RateLimitingConfig("metrics/unlimited", 0.0),
                    new RateLimitingConfig("projectOverride/limited", RATE),
                    new RateLimitingConfig("projectOverride/.*", 0.0),
                    // This more specific rule must lose to the earlier project rule.
                    new RateLimitingConfig("projectOverride/unlimited", RATE),
                    new RateLimitingConfig("repoOverride/unlimited", 0.0),
                    new RateLimitingConfig(FALLBACK_PATTERN, RATE));
        }

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("default").join();
            for (String repo : new String[] {
                    "one", "two", "endpoints", "git", "gitSuffix", "watch", "auth", "revert", "management",
                    "revertFirst" }) {
                client.createRepository("default", repo).join();
            }
            client.createProject("other").join();
            client.createRepository("other", "one").join();
            client.createProject("api").join();
            client.createRepository("api", "v1").join();
            for (String project : new String[] { "projectOverride", "repoOverride" }) {
                client.createProject(project).join();
                client.createRepository(project, "unlimited").join();
                client.createRepository(project, "limited").join();
            }
            client.createProject("metrics").join();
            for (String repo : new String[] { "one", "two", "unlimited" }) {
                client.createRepository("metrics", repo).join();
            }
            client.createProject("unmatched").join();
            client.createRepository("unmatched", "repo").join();
        }
    };

    @Test
    void independentRepositoryBudgets() throws Exception {
        assertThat(push("default", "one", 1).status()).isEqualTo(HttpStatus.OK);
        assertThat(push("default", "two", 1).status()).isEqualTo(HttpStatus.OK);
        assertThat(push("other", "one", 1).status()).isEqualTo(HttpStatus.OK);

        final AggregatedHttpResponse rejected = push("default", "one", 2);
        assertThat(rejected.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.headers().contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(Jackson.readTree(rejected.contentUtf8()).get("message").textValue())
                .isEqualTo("Repository content push rate limit exceeded.");
        assertThat(push("default", "two", 2).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(push("other", "one", 2).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @ParameterizedTest
    @ValueSource(strings = { "projectOverride", "repoOverride" })
    void projectAndRepositoryOverrides(String project) {
        assertThat(push(project, "unlimited", 1).status()).isEqualTo(HttpStatus.OK);
        assertThat(push(project, "unlimited", 2).status()).isEqualTo(HttpStatus.OK);
        assertThat(push(project, "limited", 1).status()).isEqualTo(HttpStatus.OK);
        assertThat(push(project, "limited", 2).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        if ("projectOverride".equals(project)) {
            assertThat(counter("projectOverride/.*", "passed").count()).isEqualTo(2);
            assertThat(counter("projectOverride/unlimited", "passed").count()).isZero();
            assertThat(counter("projectOverride/unlimited", "rejected").count()).isZero();
        }
    }

    @Test
    void unmatchedRepositoriesAreUnlimited() {
        assertThat(push("unmatched", "repo", 1).status()).isEqualTo(HttpStatus.OK);
        assertThat(push("unmatched", "repo", 2).status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void metricsAggregateByMatchedPatternWithIndependentBudgets() {
        final Counter passed = counter(METRIC_PATTERN, "passed");
        final Counter rejected = counter(METRIC_PATTERN, "rejected");
        assertThat(passed.count()).isZero();
        assertThat(rejected.count()).isZero();

        assertThat(push("metrics", "one", 1).status()).isEqualTo(HttpStatus.OK);
        assertThat(push("metrics", "two", 1).status()).isEqualTo(HttpStatus.OK);
        assertThat(push("metrics", "one", 2).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(push("metrics", "two", 2).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(passed.count()).isEqualTo(2);
        assertThat(rejected.count()).isEqualTo(2);
        assertThat(passed.getId().getTags()).hasSize(2);
    }

    @Test
    void unlimitedRuleCountsPassedRequests() {
        assertThat(push("metrics", "unlimited", 1).status()).isEqualTo(HttpStatus.OK);
        assertThat(push("metrics", "unlimited", 2).status()).isEqualTo(HttpStatus.OK);
        assertThat(counter("metrics/unlimited", "passed").count()).isEqualTo(2);
        assertThat(counter("metrics/unlimited", "rejected").count()).isZero();
    }

    @Test
    void readsAndPreviewsDoNotUsePushBudget() {
        final double passedBefore = counter(FALLBACK_PATTERN, "passed").count();
        final double rejectedBefore = counter(FALLBACK_PATTERN, "rejected").count();
        for (int i = 0; i < 2; i++) {
            assertThat(getRevision("default", "endpoints").status()).isEqualTo(HttpStatus.OK);
            assertThat(post("/api/v1/projects/default/repos/endpoints/preview", pushBody(1)).status())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(push("default", "endpoints", 1).status()).isEqualTo(HttpStatus.OK);
        assertThat(push("default", "endpoints", 2).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        for (int i = 0; i < 2; i++) {
            assertThat(dogma.httpClient().get("/api/v1/projects/default/repos/endpoints/contents/value.txt")
                            .aggregate().join().status()).isEqualTo(HttpStatus.OK);
            assertThat(dogma.httpClient().get("/api/v0/projects/default/repositories/endpoints/revision/-1")
                            .aggregate().join().status()).isEqualTo(HttpStatus.OK);
        }
        assertThat(counter(FALLBACK_PATTERN, "passed").count()).isEqualTo(passedBefore + 1);
        assertThat(counter(FALLBACK_PATTERN, "rejected").count()).isEqualTo(rejectedBefore + 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void pushAndRevertShareBudget(boolean revertFirst) {
        final String repoName = revertFirst ? "revertFirst" : "revert";
        final Repository repository = dogma.dogma().projectManager().get("default").repos().get(repoName);
        // Seed content without consuming a permit so a revert can be the first limited request.
        repository.commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                          "Prepare revert", Change.ofTextUpsert("/value.txt", "initial")).join();
        final String revertPath = "/api/v1/projects/default/repos/" + repoName + "/revert";
        final String revertBody =
                "{\"targetRevision\":1,\"commitMessage\":{\"summary\":\"Revert\"}}";
        final double passedBefore = counter(FALLBACK_PATTERN, "passed").count();
        final double rejectedBefore = counter(FALLBACK_PATTERN, "rejected").count();
        if (revertFirst) {
            assertThat(post(revertPath, revertBody).status()).isEqualTo(HttpStatus.OK);
            assertThat(push("default", repoName, 1).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        } else {
            assertThat(push("default", repoName, 1).status()).isEqualTo(HttpStatus.OK);
            assertThat(post(revertPath, revertBody).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        assertThat(repository.normalizeNow(Revision.HEAD).major()).isEqualTo(3);
        assertThat(counter(FALLBACK_PATTERN, "passed").count()).isEqualTo(passedBefore + 1);
        assertThat(counter(FALLBACK_PATTERN, "rejected").count()).isEqualTo(rejectedBefore + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "git", "gitSuffix.git" })
    void gitDoesNotUsePushBudget(String repoName) {
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.GET, "/default/" + repoName + "/info/refs?service=git-upload-pack",
                HttpHeaderNames.GIT_PROTOCOL, VERSION_2_REQUEST);
        assertThat(dogma.httpClient().execute(headers).aggregate().join().status()).isEqualTo(HttpStatus.OK);
        final String repository = repoName.endsWith(".git") ? "gitSuffix" : "git";
        assertThat(push("default", repository, 1).status()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 2; i++) {
            assertThat(dogma.httpClient().execute(headers).aggregate().join().status())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(push("default", repository, 2).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void gitPathOverlappingApiPrefixIsNotRateLimited() {
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.GET, "/api/v1/info/refs?service=git-upload-pack",
                HttpHeaderNames.GIT_PROTOCOL, VERSION_2_REQUEST);
        for (int i = 0; i < 2; i++) {
            assertThat(dogma.httpClient().execute(headers).aggregate().join().status())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(push("api", "v1", 1).status()).isEqualTo(HttpStatus.OK);
        assertThat(dogma.httpClient().execute(headers).aggregate().join().status()).isEqualTo(HttpStatus.OK);
        assertThat(push("api", "v1", 2).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void watchIsNotRateLimited() {
        assertThat(push("default", "watch", 1).status()).isEqualTo(HttpStatus.OK);
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.GET, "/api/v1/projects/default/repos/watch/contents",
                HttpHeaderNames.IF_NONE_MATCH, "-1", HttpHeaderNames.PREFER, "wait=1");
        assertThat(dogma.httpClient().execute(headers).aggregate().join().status())
                .isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void authenticateBeforeRateLimiting() {
        final double passedBefore = counter(FALLBACK_PATTERN, "passed").count();
        final double rejectedBefore = counter(FALLBACK_PATTERN, "rejected").count();
        final WebClient unauthenticated = WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort());
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.POST, "/api/v1/projects/default/repos/auth/contents",
                HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        assertThat(unauthenticated.execute(headers, pushBody(1)).aggregate().join().status())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(push("default", "auth", 1).status()).isEqualTo(HttpStatus.OK);
        assertThat(unauthenticated.execute(headers, pushBody(2)).aggregate().join().status())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(push("default", "auth", 2).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(counter(FALLBACK_PATTERN, "passed").count()).isEqualTo(passedBefore + 1);
        assertThat(counter(FALLBACK_PATTERN, "rejected").count()).isEqualTo(rejectedBefore + 1);
    }

    @Test
    void managementRequestsAreNotRateLimited() {
        assertThat(push("default", "management", 1).status()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 2; i++) {
            assertThat(dogma.httpClient().get("/api/v1/projects/default/repos").aggregate().join().status())
                    .isEqualTo(HttpStatus.OK);
            assertThat(dogma.httpClient().get("/api/v1/projects/default/repos/management")
                            .aggregate().join().status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void nonexistentResourcesAreNotRateLimited() {
        final double passedBefore = counter(FALLBACK_PATTERN, "passed").count();
        final double rejectedBefore = counter(FALLBACK_PATTERN, "rejected").count();
        for (int i = 0; i < 2; i++) {
            assertThat(push("missing", "repo", 1).status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(push("default", "missing", 1).status()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(counter(FALLBACK_PATTERN, "passed").count()).isEqualTo(passedBefore);
        assertThat(counter(FALLBACK_PATTERN, "rejected").count()).isEqualTo(rejectedBefore);
        dogma.client().createRepository("default", "missing").join();
        assertThat(push("default", "missing", 1).status()).isEqualTo(HttpStatus.OK);
    }

    private static Counter counter(String pattern, String result) {
        return meterRegistry.get("content.push.rate.limiting.requests")
                            .tags("pattern", pattern, "result", result).counter();
    }

    private static AggregatedHttpResponse push(String project, String repo, int value) {
        return post("/api/v1/projects/" + project + "/repos/" + repo + "/contents", pushBody(value));
    }

    private static AggregatedHttpResponse post(String path, String body) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, path,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        return dogma.httpClient().execute(headers, body).aggregate().join();
    }

    private static String pushBody(int value) {
        return "{\"path\":\"/value.txt\",\"type\":\"UPSERT_TEXT\",\"content\":\"" + value + "\"," +
               "\"commitMessage\":{\"summary\":\"Update value\"}}";
    }

    private static AggregatedHttpResponse getRevision(String project, String repo) {
        return dogma.httpClient().get("/api/v1/projects/" + project + "/repos/" + repo + "/revision/-1")
                    .aggregate().join();
    }
}
