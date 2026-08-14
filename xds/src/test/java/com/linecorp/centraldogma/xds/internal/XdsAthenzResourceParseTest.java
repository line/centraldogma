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

import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.protobuf.Any;

import com.linecorp.armeria.xds.athenz.AthenzFilterConfig.AccessTokenConstraintConfig;
import com.linecorp.centraldogma.internal.Yaml;

import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import jp.co.lycorp.ftd.athenz.v1.AthenzAccessToken.AccessTokenConstraint;

class XdsAthenzResourceParseTest {

    private static Listener athenzListener() {
        // An inbound Athenz constraint filter config, packed as the http filter typed_config Any.
        final AccessTokenConstraintConfig constraintConfig =
                AccessTokenConstraintConfig.newBuilder()
                                           .setZtsClusterName("zts-cluster")
                                           .setAccessTokenConstraint(
                                                   AccessTokenConstraint.newBuilder()
                                                                        .setConstraintDomain("my.domain")
                                                                        .setSyntaxVersion(1))
                                           .build();
        final HttpConnectionManager manager =
                HttpConnectionManager.newBuilder()
                                     .setStatPrefix("ingress_http")
                                     .addHttpFilters(
                                             HttpFilter.newBuilder()
                                                       .setName("armeria.xds.athenz.access_token_constraint")
                                                       .setTypedConfig(Any.pack(constraintConfig)))
                                     .build();
        return Listener.newBuilder()
                       .setName("groups/foo/listeners/athenz")
                       .setApiListener(ApiListener.newBuilder().setApiListener(Any.pack(manager)))
                       .build();
    }

    @Test
    void athenzTypedConfigRoundTrips() throws Exception {
        final Listener listener = athenzListener();

        // Serialize (as the serving/read path does) — the marshaller must resolve the Athenz Any type.
        final String yaml = XdsResourceManager.toYamlBodyString(listener);
        assertThat(yaml).contains("type.googleapis.com/armeria.xds.athenz.AccessTokenConstraintConfig");

        // Parse it back (as the create/update and control-plane paths do).
        final Listener.Builder builder = Listener.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(Yaml.readTree(yaml).traverse(), builder);
        assertThat(builder.build()).isEqualTo(listener);
    }

    @Test
    void athenzFilterConfigUnpacksFromParsedResource() throws Exception {
        final Listener parsed = XdsResourceManager.parseYaml(
                XdsResourceManager.toYamlBodyString(athenzListener()), Listener.newBuilder());
        final HttpConnectionManager manager =
                parsed.getApiListener().getApiListener().unpack(HttpConnectionManager.class);
        final Any typedConfig = manager.getHttpFilters(0).getTypedConfig();
        assertThat(typedConfig.getTypeUrl())
                .isEqualTo("type.googleapis.com/armeria.xds.athenz.AccessTokenConstraintConfig");
        final AccessTokenConstraintConfig constraintConfig =
                typedConfig.unpack(AccessTokenConstraintConfig.class);
        assertThat(constraintConfig.getZtsClusterName()).isEqualTo("zts-cluster");
        assertThat(constraintConfig.getAccessTokenConstraint().getConstraintDomain()).isEqualTo("my.domain");
    }
}
