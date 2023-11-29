/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.centraldogma.it;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.login;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class NonRandomTokenTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.administrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    @Test
    void createNonRandomToken() throws Exception {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse response = login(client,
                                                      TestAuthMessageUtil.USERNAME,
                                                      TestAuthMessageUtil.PASSWORD);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        final String sessionId = Jackson.readValue(response.content().array(), AccessToken.class)
                                        .accessToken();
        final WebClient adminClient = WebClient.builder(client.uri())
                                               .auth(AuthToken.ofOAuth2(sessionId)).build();

        final HttpRequest request = HttpRequest.builder()
                                               .post("/api/v1/tokens")
                                               .content(MediaType.FORM_DATA,
                                                        "secret=appToken-secret&isAdmin=true&appId=foo")
                                               .build();
        AggregatedHttpResponse res = adminClient.execute(request).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);
        res = adminClient.get("/api/v1/tokens").aggregate().join();
        assertThat(res.contentUtf8()).contains("\"secret\":\"appToken-secret\"");
    }
}
