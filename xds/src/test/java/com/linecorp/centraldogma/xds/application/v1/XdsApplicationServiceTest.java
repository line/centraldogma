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
package com.linecorp.centraldogma.xds.application.v1;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Empty;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;
import com.linecorp.centraldogma.xds.application.v1.XdsApplicationServiceGrpc.XdsApplicationServiceBlockingStub;

import io.grpc.Status;

class XdsApplicationServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void createApplicationViaHttp() {
        // Invalid name.
        AggregatedHttpResponse response = createApplication("invalid/foo");
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);

        response = createApplication("applications/foo");
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
        assertThatJson(response.contentUtf8()).isEqualTo("{\"name\":\"applications/foo\"}");

        // Cannot create with the same name.
        response = createApplication("applications/foo");
        assertThat(response.status()).isSameAs(HttpStatus.CONFLICT);
        assertThat(response.headers().get("grpc-status"))
                .isEqualTo(Integer.toString(Status.ALREADY_EXISTS.getCode().value()));
    }

    private static AggregatedHttpResponse createApplication(String applicationName) {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, "/api/v1/xds/applications")
                                                     .contentType(MediaType.JSON_UTF_8).build();
        return dogma.httpClient().execute(headers, "{\"application\": {\"name\":\"" + applicationName + "\"}}")
            .aggregate().join();
    }

    @Test
    void deleteApplicationViaHttp() {
        AggregatedHttpResponse response = deleteApplication("applications/bar");
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createApplication("applications/bar");
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        // Add permission test.

        response = deleteApplication("applications/bar");
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
        assertThat(response.contentUtf8()).isEqualTo("{}");
    }

    private static AggregatedHttpResponse deleteApplication(String applicationName) {
        return dogma.httpClient().delete("/api/v1/xds/" + applicationName).aggregate().join();
    }

    @Test
    void createAndDeleteApplicationViaStub() {
        final XdsApplicationServiceBlockingStub client = GrpcClients.newClient(
                dogma.httpClient().uri(), XdsApplicationServiceBlockingStub.class);
        final Application application = client.createApplication(
                CreateApplicationRequest.newBuilder()
                                        .setApplication(Application.newBuilder()
                                                                   .setName("applications/baz"))
                                        .build());
        assertThat(application.getName()).isEqualTo("applications/baz");
        // No exception is thrown.
        final Empty ignored = client.deleteApplication(DeleteApplicationRequest.newBuilder()
                                                                               .setName("applications/baz")
                                                                               .build());
    }
}
