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

package com.linecorp.centraldogma.webapp;

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
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.server.ZooKeeperServerConfig;
import com.linecorp.centraldogma.server.auth.shiro.ShiroAuthProviderFactory;

/**
 * A {@link ShiroCentralDogmaTestServer} variant that runs in replicated (ZooKeeper) mode with a
 * single-replica quorum, so that replication-only features (e.g. repository recovery) show up in the
 * web UI during development.
 */
final class ReplicatedShiroCentralDogmaTestServer {

    private static final int PORT = 36462;

    @SuppressWarnings("UncommentedMain")
    public static void main(String[] args) throws IOException {
        final Path rootDir = Files.createTempDirectory("dogma-replicated-test");
        final CentralDogma server = new CentralDogmaBuilder(rootDir.toFile())
                .port(PORT, SessionProtocol.HTTP)
                .systemAdministrators(USERNAME)
                .cors("http://127.0.0.1:36462", "http://127.0.0.1:3000", "http://localhost:36462",
                      "http://localhost:3000")
                .authProviderFactory(new ShiroAuthProviderFactory(unused -> {
                    final Ini iniConfig = new Ini();
                    final Ini.Section users = iniConfig.addSection("users");
                    users.put(USERNAME, PASSWORD);
                    users.put(USERNAME2, PASSWORD2);
                    return iniConfig;
                }))
                .replication(new ZooKeeperReplicationConfig(
                        1,
                        ImmutableMap.of(1, new ZooKeeperServerConfig("127.0.0.1", 36466, 36467, 36468,
                                                                     null, null)),
                        "test-secret-for-replication-0123456789abcdef"))
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
    }

    private ReplicatedShiroCentralDogmaTestServer() {}
}
