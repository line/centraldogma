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
package com.linecorp.centraldogma.xds.group.v1;

import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.deleteGroup;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Empty;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;
import com.linecorp.centraldogma.xds.group.v1.XdsGroupServiceGrpc.XdsGroupServiceBlockingStub;

import io.grpc.Status;

final class XdsGroupServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void createGroupViaHttp() {
        // Invalid name.
        AggregatedHttpResponse response = createGroup("invalid/foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);

        response = createGroup("groups/foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
        assertThatJson(response.contentUtf8()).isEqualTo("{\"name\":\"groups/foo\"}");

        // Cannot create with the same name.
        response = createGroup("groups/foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.CONFLICT);
        assertThat(response.headers().get("grpc-status"))
                .isEqualTo(Integer.toString(Status.ALREADY_EXISTS.getCode().value()));
    }

    @Test
    void deleteGroupViaHttp() {
        AggregatedHttpResponse response = deleteGroup("groups/bar", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createGroup("groups/bar", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        // Add permission test.

        response = deleteGroup("groups/bar", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
        assertThat(response.contentUtf8()).isEqualTo("{}");
    }

    @Test
    void createAndDeleteGroupViaStub() {
        final XdsGroupServiceBlockingStub client =
                GrpcClients.builder(dogma.httpClient().uri())
                           .setHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                           .build(XdsGroupServiceBlockingStub.class);
        final Group group = client.createGroup(
                CreateGroupRequest.newBuilder()
                                  .setGroup(Group.newBuilder()
                                                 .setName("groups/baz"))
                                  .build());
        assertThat(group.getName()).isEqualTo("groups/baz");
        // No exception is thrown.
        final Empty ignored = client.deleteGroup(DeleteGroupRequest.newBuilder()
                                                                   .setName("groups/baz")
                                                                   .build());
    }
}
