/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.server.internal.mirror.DefaultMirroringServicePlugin.mirroringServicePluginConfig;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringServicePluginConfig.DEFAULT_MAX_NUM_BYTES_PER_MIRROR;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringServicePluginConfig.DEFAULT_MAX_NUM_FILES_PER_MIRROR;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaConfig;

class DefaultMirroringServicePluginTest {

    @Test
    void pluginConfig() throws Exception {
        final CentralDogmaConfig centralDogmaConfig = Jackson.readValue("{\n" +
                                                                        "  \"dataDir\": \"./data\",\n" +
                                                                        "  \"ports\": [\n" +
                                                                        "    {\n" +
                                                                        "      \"localAddress\": {\n" +
                                                                        "        \"host\": \"*\",\n" +
                                                                        "        \"port\": 36462\n" +
                                                                        "      },\n" +
                                                                        "      \"protocols\": [\n" +
                                                                        "        \"https\",\n" +
                                                                        "        \"http\",\n" +
                                                                        "        \"proxy\"\n" +
                                                                        "      ]\n" +
                                                                        "    }\n" +
                                                                        "  ],\n" +
                                                                        "  \"plugins\": [" +
                                                                        "    {\n" +
                                                                        "      \"name\": \"mirror\",\n" +
                                                                        "      \"config\": {\n" +
                                                                        "        \"numMirroringThreads\": 1" +
                                                                        "      }" +
                                                                        "    }" +
                                                                        "  ]\n" +
                                                                        '}',
                                                                        CentralDogmaConfig.class);
        final MirroringServicePluginConfig mirroringServicePluginConfig = mirroringServicePluginConfig(
                centralDogmaConfig.pluginConfigs().get(0));
        assertThat(mirroringServicePluginConfig).isNotNull();
        assertThat(mirroringServicePluginConfig.numMirroringThreads()).isOne();
        assertThat(mirroringServicePluginConfig.maxNumFilesPerMirror())
                .isEqualTo(DEFAULT_MAX_NUM_FILES_PER_MIRROR);
        assertThat(mirroringServicePluginConfig.maxNumBytesPerMirror())
                .isEqualTo(DEFAULT_MAX_NUM_BYTES_PER_MIRROR);
    }
}
