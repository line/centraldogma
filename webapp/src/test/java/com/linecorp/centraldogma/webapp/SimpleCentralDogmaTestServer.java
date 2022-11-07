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

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;

final class SimpleCentralDogmaTestServer {

    private static final int PORT = 36462;

    public static void main(String[] args) throws IOException {
        final Path rooDir = Files.createTempDirectory("dogma-test");
        final CentralDogma server = new CentralDogmaBuilder(rooDir.toFile())
                // Enable the legacy webapp
                // .webAppEnabled(true)
                .port(PORT, SessionProtocol.HTTP)
                .build();
        server.start().join();
        scaffold();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }

    private static void scaffold() throws UnknownHostException {
        final com.linecorp.centraldogma.client.CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .host("127.0.0.1", PORT)
                .build();
        client.createProject("foo").join();
        client.createRepository("foo", "bar").join();
    }
}
