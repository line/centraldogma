/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.auth.shiro;

import static com.linecorp.centraldogma.testing.internal.authentication.TestAuthenticationMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.authentication.TestAuthenticationMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.authentication.TestAuthenticationMessageUtil.WRONG_PASSWORD;
import static com.linecorp.centraldogma.testing.internal.authentication.TestAuthenticationMessageUtil.WRONG_SESSION_ID;
import static com.linecorp.centraldogma.testing.internal.authentication.TestAuthenticationMessageUtil.login;
import static com.linecorp.centraldogma.testing.internal.authentication.TestAuthenticationMessageUtil.loginWithBasicAuth;
import static com.linecorp.centraldogma.testing.internal.authentication.TestAuthenticationMessageUtil.logout;
import static com.linecorp.centraldogma.testing.internal.authentication.TestAuthenticationMessageUtil.usersMe;
import static org.assertj.core.api.Assertions.assertThat;

import org.apache.shiro.config.Ini;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.auth.AuthenticationProvider;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class ShiroLoginAndLogoutTest {

    @Rule
    public final CentralDogmaRule rule = new CentralDogmaRule() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new ShiroAuthenticationProviderFactory(unused -> {
                final Ini iniConfig = new Ini();
                iniConfig.addSection("users").put(USERNAME, PASSWORD);
                return iniConfig;
            }));
            builder.webAppEnabled(true);
        }
    };

    private HttpClient client;

    @Before
    public void setClient() {
        client = rule.httpClient();
    }

    @Test
    public void password() throws Exception { // grant_type=password
        loginAndLogout(login(client, USERNAME, PASSWORD));
    }

    private void loginAndLogout(AggregatedHttpMessage loginRes) throws Exception {
        assertThat(loginRes.status()).isEqualTo(HttpStatus.OK);

        // Ensure authorization works.
        final AccessToken accessToken = Jackson.readValue(loginRes.content().toStringUtf8(), AccessToken.class);
        final String sessionId = accessToken.accessToken();

        assertThat(usersMe(client, sessionId).status()).isEqualTo(HttpStatus.OK);

        // Log out.
        assertThat(logout(client, sessionId).status()).isEqualTo(HttpStatus.OK);
        assertThat(usersMe(client, sessionId).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void basicAuth() throws Exception {
        loginAndLogout(loginWithBasicAuth(client, USERNAME, PASSWORD));
    }

    @Test
    public void incorrectLogin() throws Exception {
        assertThat(login(client, USERNAME, WRONG_PASSWORD).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void incorrectLogout() throws Exception {
        assertThat(logout(client, WRONG_SESSION_ID).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void shouldUseBuiltinWebPages() throws Exception {
        AggregatedHttpMessage resp;
        resp = client.get(AuthenticationProvider.LOGIN_PATH).aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
        assertThat(resp.headers().get(HttpHeaderNames.LOCATION))
                .isEqualTo(AuthenticationProvider.BUILTIN_WEB_LOGIN_PATH);

        resp = client.get(AuthenticationProvider.LOGOUT_PATH).aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
        assertThat(resp.headers().get(HttpHeaderNames.LOCATION))
                .isEqualTo(AuthenticationProvider.BUILTIN_WEB_LOGOUT_PATH);
    }
}
