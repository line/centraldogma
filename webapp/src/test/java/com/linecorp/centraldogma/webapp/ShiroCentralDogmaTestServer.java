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

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.shiro.config.Ini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.ZoneConfig;
import com.linecorp.centraldogma.server.auth.shiro.ShiroAuthProviderFactory;
import com.linecorp.centraldogma.server.credential.CreateCredentialRequest;
import com.linecorp.centraldogma.server.internal.credential.NoneCredential;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;

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
                .systemAdministrators(USERNAME)
                .cors("127.0.0.1", "localhost")
                .authProviderFactory(new ShiroAuthProviderFactory(unused -> {
                    final Ini iniConfig = new Ini();
                    final Ini.Section users = iniConfig.addSection("users");
                    users.put(USERNAME, PASSWORD);
                    users.put(USERNAME2, PASSWORD2);
                    return iniConfig;
                }))
                .pluginConfigs(new MirroringServicePluginConfig(true, null, null, null, true))
                .zone(new ZoneConfig("zoneA", ImmutableList.of("zoneA", "zoneB", "zoneC")))
                .build();
        server.start().join();
        scaffold();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }

    private static void scaffold() throws UnknownHostException, JsonProcessingException {
        final String token = getAccessToken(WebClient.of("http://127.0.0.1:" + PORT), USERNAME, PASSWORD,
                                            "appId", true);
        final com.linecorp.centraldogma.client.CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .host("127.0.0.1", PORT)
                .accessToken(token)
                .build();
        client.createProject("foo").join();
        client.createRepository("foo", "bar").join();

        final BlockingWebClient webClient = WebClient.builder("http://127.0.0.1:" + PORT)
                                                     .auth(AuthToken.ofOAuth2(token))
                                                     .build()
                                                     .blocking();
        final AggregatedHttpResponse res = webClient.prepare()
                                                    .post("/api/v1/projects/foo/credentials")
                                                    .contentJson(new CreateCredentialRequest(
                                                            "none",
                                                            new NoneCredential(credentialName("foo", "none"))))
                                                    .execute();
        assert res.status() == HttpStatus.OK : res.status();
    }

    private ShiroCentralDogmaTestServer() {}
}
