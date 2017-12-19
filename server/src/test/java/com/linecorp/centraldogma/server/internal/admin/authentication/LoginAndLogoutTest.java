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

package com.linecorp.centraldogma.server.internal.admin.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.apache.shiro.config.Ini;
import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class LoginAndLogoutTest {

    static final String USERNAME = "foo";
    static final String PASSWORD = "bar";
    static final String WRONG_PASSWORD = "baz";
    static final String WRONG_SESSION_ID = "00000000-0000-0000-0000-000000000000";

    static Ini newSecurityConfig() {
        final Ini ini = new Ini();
        ini.addSection("users").put(USERNAME, PASSWORD);
        return ini;
    }

    static AggregatedHttpMessage login(CentralDogmaRule rule, String username, String password) {
        return rule.httpClient().execute(
                HttpHeaders.of(HttpHeaderNames.METHOD, "POST",
                               HttpHeaderNames.PATH, "/api/v0/authenticate",
                               HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA.toString()),
                "username=" + username + "&password=" + password + "&remember_me=true",
                StandardCharsets.US_ASCII).aggregate().join();
    }

    static AggregatedHttpMessage logout(CentralDogmaRule rule, String sessionId) {
        return rule.httpClient().execute(
                HttpHeaders.of(HttpHeaderNames.METHOD, "POST",
                               HttpHeaderNames.PATH, "/api/v0/logout",
                               HttpHeaderNames.AUTHORIZATION,
                               "bearer " + sessionId)).aggregate().join();
    }

    static AggregatedHttpMessage usersMe(CentralDogmaRule rule, String sessionId) {
        return rule.httpClient().execute(
                HttpHeaders.of(HttpHeaderNames.METHOD, "GET",
                               HttpHeaderNames.PATH, "/api/v0/users/me",
                               HttpHeaderNames.AUTHORIZATION,
                               "bearer " + sessionId)).aggregate().join();
    }

    @Rule
    public final CentralDogmaRule rule = new CentralDogmaRule() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.securityConfig(newSecurityConfig());
            builder.webAppEnabled(true);
        }
    };

    @Test
    public void loginAndLogout() throws Exception {
        // Log in.
        final AggregatedHttpMessage loginRes = login(rule, USERNAME, PASSWORD);
        assertThat(loginRes.status()).isEqualTo(HttpStatus.OK);

        // Ensure authorization works.
        final String sessionId = loginRes.content().toStringAscii();
        assertThat(usersMe(rule, sessionId).status()).isEqualTo(HttpStatus.OK);

        // Log out.
        assertThat(logout(rule, sessionId).status()).isEqualTo(HttpStatus.OK);
        assertThat(usersMe(rule, sessionId).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void incorrectLogin() throws Exception {
        assertThat(login(rule, USERNAME, WRONG_PASSWORD).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void incorrectLogout() throws Exception {
        assertThat(logout(rule, WRONG_SESSION_ID).status()).isEqualTo(HttpStatus.OK);
    }
}
