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
package com.linecorp.centraldogma.server.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class JacksonRequestConverterFunctionTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void createProject() {
        // When creating a project, DefaultJacksonObjectMapper was used for converting JSON to
        // CreateProjectRequest.
        // https://github.com/line/armeria/blob/219ee3758b232ca9c47e03258a0a62f4cc4841c3/core/src/main/java/com/linecorp/armeria/internal/common/DefaultJacksonObjectMapperProvider.java#L30
        // The owners and members are null in the JSON so the ObjectMapper must allow null field.
        // However, if there's the scala module in the classpath, the ObjectMapper disallows null value,
        // so it fails.
        // https://github.com/line/armeria/blob/219ee3758b232ca9c47e03258a0a62f4cc4841c3/core/src/main/java/com/linecorp/armeria/internal/common/DefaultJacksonObjectMapperProvider.java#L48
        dogma.client().createProject("foo").join();
        final Set<String> projects = dogma.client().listProjects().join();
        // Admin contains intenal dogma project as well.
        assertThat(projects).containsExactlyInAnyOrder("foo", "dogma");
    }
}
