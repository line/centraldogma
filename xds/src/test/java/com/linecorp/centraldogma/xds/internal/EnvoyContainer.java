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

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import com.github.dockerjava.api.command.InspectContainerResponse;

final class EnvoyContainer extends GenericContainer<EnvoyContainer> {

    private static final Logger logger = LoggerFactory.getLogger(EnvoyContainer.class);

    private static final String CONFIG_DEST = "/etc/envoy/envoy.yaml";
    private static final String LAUNCH_ENVOY_SCRIPT = "envoy/launch_envoy.sh";
    private static final String LAUNCH_ENVOY_SCRIPT_DEST = "/usr/local/bin/launch_envoy.sh";

    static final int ADMIN_PORT = 9901;

    private final String config;
    private final Supplier<Integer> controlPlanePortSupplier;

    EnvoyContainer(String config, Supplier<Integer> controlPlanePortSupplier) {
        // this version is changed automatically by /tools/update-sha.sh:57
        // if you change it make sure to reflect changes there
        super("envoyproxy/envoy:v1.27.0");
        this.config = config;
        this.controlPlanePortSupplier = controlPlanePortSupplier;
    }

    @SuppressWarnings("resource")
    @Override
    protected void configure() {
        withClasspathResourceMapping(LAUNCH_ENVOY_SCRIPT, LAUNCH_ENVOY_SCRIPT_DEST, BindMode.READ_ONLY);
        withClasspathResourceMapping(config, CONFIG_DEST, BindMode.READ_ONLY);
        withCommand("/bin/bash", LAUNCH_ENVOY_SCRIPT_DEST, Integer.toString(controlPlanePortSupplier.get()),
                    CONFIG_DEST, "-l", "debug");

        addExposedPort(ADMIN_PORT);
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        followOutput(new Slf4jLogConsumer(logger).withPrefix("ENVOY"));

        super.containerIsStarting(containerInfo);
    }
}
