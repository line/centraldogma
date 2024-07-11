/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.centraldogma.webapp;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.login;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.shiro.config.Ini;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.auth.shiro.ShiroAuthProviderFactory;

final class ShiroCentralDogmaTestServer {

    private static final int PORT = 36462;

    /**
     * Start a new Central Dogma server with Apache Shiro.
     */
    @SuppressWarnings("UncommentedMain")
    public static void main(String[] args) throws IOException {
        final Path rooDir = Files.createTempDirectory("dogma-test");
        final CentralDogma server = new CentralDogmaBuilder(rooDir.toFile())
                // Enable the legacy webapp
                // .webAppEnabled(true)
                .port(PORT, SessionProtocol.HTTP)
                .administrators(USERNAME)
                .cors("*")
                .authProviderFactory(new ShiroAuthProviderFactory(unused -> {
                    final Ini iniConfig = new Ini();
                    final Ini.Section users = iniConfig.addSection("users");
                    users.put(USERNAME, PASSWORD);
                    users.put(USERNAME2, PASSWORD2);
                    return iniConfig;
                }))
                .build();
        server.start().join();
        scaffold();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }

    private static void scaffold() throws UnknownHostException, JsonProcessingException {
        final com.linecorp.centraldogma.client.CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .host("127.0.0.1", PORT)
                .accessToken(getSessionToken())
                .build();
        client.createProject("foo").join();
        client.createRepository("foo", "bar").join();
    }

    private static String getSessionToken() throws JsonProcessingException {
        final WebClient client = WebClient.of("http://127.0.0.1:" + PORT);
        final AggregatedHttpResponse response = login(client, USERNAME, PASSWORD);
        // Ensure authorization works.
        final AccessToken accessToken = Jackson.readValue(response.contentUtf8(), AccessToken.class);
        return accessToken.accessToken();
    }

    private ShiroCentralDogmaTestServer() {}
}
