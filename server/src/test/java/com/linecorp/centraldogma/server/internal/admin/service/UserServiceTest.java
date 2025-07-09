/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class UserServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.webAppEnabled(true);
        }
    };

    @Test
    void shouldNotReturnWwwAuthenticateHeaderOnUnauthorized() {
        final BlockingWebClient client = BlockingWebClient.of(dogma.httpClient().uri());
        final AggregatedHttpResponse response = client.get("/api/v0/users/me");
        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.headers().get(HttpHeaderNames.WWW_AUTHENTICATE)).isNull();

        final String accessToken =
                TestAuthMessageUtil.getAccessToken(dogma.httpClient(),
                                                   TestAuthMessageUtil.USERNAME,
                                                   TestAuthMessageUtil.PASSWORD);
        final User user = client.prepare()
                                .get("/api/v0/users/me")
                                .header(HttpHeaderNames.AUTHORIZATION,
                                        "Bearer " + accessToken)
                                .asJson(User.class)
                                .execute()
                                .content();
        assertThat(user.login()).isEqualTo(TestAuthMessageUtil.USERNAME);
    }
}
