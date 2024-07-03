/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.xds.internal;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

final class EchoContainer extends GenericContainer<EchoContainer> {

    // Using 8080 is fine because this port is used in the container. The port is exposed with a different
    // port number which you can get via ContainerState.getMappedPort().
    static final Integer PORT = 8080;
    static final Integer NO_ECHO_PORT = 8081;
    private final boolean echo;

    EchoContainer(boolean echo) {
        super("mendhak/http-https-echo:latest");
        this.echo = echo;
    }

    @Override
    protected void configure() {
        if (echo) {
            addExposedPort(PORT);
        } else {
            addExposedPort(NO_ECHO_PORT);
            withEnv("HTTP_PORT", Integer.toString(NO_ECHO_PORT));
            withEnv("ECHO_BACK_TO_CLIENT", "false");
        }
        waitingFor(Wait.forHttp("/").forStatusCode(200));
    }
}
