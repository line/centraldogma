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

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;

import org.apache.shiro.config.Ini;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.TlsConfig;
import com.linecorp.centraldogma.server.ZoneConfig;
import com.linecorp.centraldogma.server.auth.MtlsConfig;
import com.linecorp.centraldogma.server.auth.shiro.ShiroAuthProviderFactory;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;

final class ShiroCentralDogmaMtlsTestServer {

    private static final int PORT = 36462;

    /**
     * Start a new Central Dogma server with Apache Shiro.
     */
    @SuppressWarnings("UncommentedMain")
    public static void main(String[] args) throws IOException, CertificateException {
        final SelfSignedCertificate serverCert = new SelfSignedCertificate();
        final Path rootDir = Files.createTempDirectory("dogma-test");
        final CentralDogma server = new CentralDogmaBuilder(rootDir.toFile())
                // Enable the legacy webapp
                // .webAppEnabled(true)
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
                // Needs for enabling mTLS.
                .port(443, SessionProtocol.HTTPS)
                .tls(new TlsConfig(serverCert.certificate(), serverCert.privateKey(), null, null, null))
                .mtlsConfig(new MtlsConfig(true, ImmutableList.of()))
                .pluginConfigs(new MirroringServicePluginConfig(true, null, null, null, true))
                .zone(new ZoneConfig("zoneA", ImmutableList.of("zoneA", "zoneB", "zoneC")))
                .build();
        server.start().join();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }

    private ShiroCentralDogmaMtlsTestServer() {}
}
