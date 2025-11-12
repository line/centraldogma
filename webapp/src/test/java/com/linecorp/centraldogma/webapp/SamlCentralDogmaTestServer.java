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

package com.linecorp.centraldogma.webapp;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.EncryptionAtRestConfig;
import com.linecorp.centraldogma.server.ZoneConfig;
import com.linecorp.centraldogma.server.auth.saml.SamlAuthProperties;
import com.linecorp.centraldogma.server.auth.saml.SamlAuthProperties.Idp;
import com.linecorp.centraldogma.server.auth.saml.SamlAuthProperties.KeyStore;
import com.linecorp.centraldogma.server.auth.saml.SamlAuthProviderFactory;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;

final class SamlCentralDogmaTestServer {

    private static final int PORT = 36462;

    /**
     * Start a new Central Dogma server with SAML authentication enabled.
     */
    @SuppressWarnings("UncommentedMain")
    public static void main(String[] args) throws IOException {
        final Server samlIdpServer = SamlIdpServer.newServer();
        samlIdpServer.start().join();

        final Path rootDir = Files.createTempDirectory("dogma-test");
        final CentralDogma server = new CentralDogmaBuilder(rootDir.toFile())
                .webAppEnabled(true)
                .encryptionAtRest(new EncryptionAtRestConfig(true, true, "kekId"))
                .port(PORT, SessionProtocol.HTTP)
                .systemAdministrators(USERNAME)
                .cors("http://127.0.0.1:36462", "http://127.0.0.1:3000", "http://localhost:36462",
                      "http://localhost:3000")
                .authProviderFactory(new SamlAuthProviderFactory())
                .authProviderProperties(new SamlAuthProperties(
                        "dogma",
                        "127.0.0.1",
                        null,
                        null,
                        new KeyStore("PKCS12", "test.jks", "centraldogma",
                                     ImmutableMap.of("signing", "centraldogma", "encryption", "centraldogma"),
                                     null),
                        null,
                        new Idp("central-dogma-idp", "http://127.0.0.1:8081/sso/saml", null,
                                "signing", "encryption",
                                "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified", null),
                        false))
                .pluginConfigs(new MirroringServicePluginConfig(true, null, null, null, true))
                .zone(new ZoneConfig("zoneA", ImmutableList.of("zoneA", "zoneB", "zoneC")))
                .build();
        server.start().join();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop().join();
            samlIdpServer.stop().join();
        }));
    }

    private SamlCentralDogmaTestServer() {}
}
