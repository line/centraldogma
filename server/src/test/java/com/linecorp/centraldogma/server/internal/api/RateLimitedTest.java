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
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.centraldogma.server.RateLimitRule;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RateLimitedTest {

    @RegisterExtension
    final ServerExtension server = new ServerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void configure(ServerBuilder sb) {
            final ProjectManager projectManager = mock(ProjectManager.class, RETURNS_DEEP_STUBS);
            final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            sb.dependencyInjector(DependencyInjector.ofSingletons(
                    new RepositoryRateLimiter(
                            ImmutableList.of(new RateLimitRule(".*", 1L, TimeUnit.DAYS)),
                            projectManager, meterRegistry),
                    new GlobalRateLimiter(new RateLimitRule(2L, TimeUnit.DAYS), meterRegistry)), false);
            sb.annotatedService(new Object() {
                @Post("/alias/{projectName}/{repoName}")
                @RateLimited
                public HttpResponse alias() {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Post("/explicit/{projectName}/{repoName}")
                @GlobalRateLimited
                @RepositoryRateLimited
                public HttpResponse explicit() {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Post("/repository/{projectName}/{repoName}")
                @RepositoryRateLimited
                public HttpResponse repository() {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Post("/global/{projectName}/{repoName}")
                @GlobalRateLimited
                public HttpResponse global() {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });
        }
    };

    @ParameterizedTest
    @ValueSource(strings = { "alias", "explicit" })
    void independentAndCombinedLimits(String combined) {
        assertThat(push("repository", "one").status()).isEqualTo(HttpStatus.OK);
        assertRejection("repository", "one", "Repository");
        assertThat(push(combined, "two").status()).isEqualTo(HttpStatus.OK);
        // The global-only annotation ignores the exhausted repository budget and shares the global bucket.
        assertThat(push("global", "one").status()).isEqualTo(HttpStatus.OK);
        assertRejection("global", "one", "Server");
        // Even with both budgets exhausted, repository rejection takes precedence.
        assertRejection(combined, "two", "Repository");
        // A global rejection consumes the repository token, just as it does with explicit decorators.
        assertRejection(combined, "three", "Server");
        assertRejection(combined, "three", "Repository");
        assertThat(push("repository", "four").status()).isEqualTo(HttpStatus.OK);
    }

    private AggregatedHttpResponse push(String endpoint, String repository) {
        return server.webClient().post('/' + endpoint + "/default/" + repository, "").aggregate().join();
    }

    private void assertRejection(String endpoint, String repository, String scope) {
        final AggregatedHttpResponse response = push(endpoint, repository);
        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.contentUtf8()).contains(scope + " content push rate limit exceeded.");
    }
}
