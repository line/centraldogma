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
package com.linecorp.centraldogma.xds.internal;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

final class XdsWebEnabledFlagTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.webAppEnabled(true);
        }
    };

    @Test
    void exposesWebEnabledFlag() {
        // The xDS UI is bundled into and served by the main web app under '/app/xds'. The control plane only
        // exposes a flag at '/api/v1/xds/web' so the web app reveals the xDS link when the plugin is loaded.
        final AggregatedHttpResponse response =
                dogma.httpClient().get("/api/v1/xds/web").aggregate().join();
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).contains("application/json");
        assertThatJson(response.contentUtf8()).isEqualTo("{\"enabled\":true}");
    }
}
