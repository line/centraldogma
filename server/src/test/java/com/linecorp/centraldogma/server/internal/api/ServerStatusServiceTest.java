/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.api;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.logging.ContentPreviewingClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest.Scope;
import com.linecorp.centraldogma.server.management.ReplicationStatus;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ServerStatusServiceTest {

    @RegisterExtension
    final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
            builder.decorator(LoggingClient.newDecorator());
            builder.decorator(ContentPreviewingClient.newDecorator(1000));
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void status() {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse res = client.get(API_V1_PATH_PREFIX + "status").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("\"WRITABLE\"");
    }

    @Test
    void updateStatus_setUnwritable() {
        final AggregatedHttpResponse res = updateStatus(ReplicationStatus.REPLICATION_ONLY);

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("\"REPLICATION_ONLY\"");
    }

    @Test
    void updateStatus_setUnwritableAndNonReplicating() {
        final AggregatedHttpResponse res = updateStatus(ReplicationStatus.READ_ONLY);
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("\"READ_ONLY\"");
    }

    @Test
    void updateStatus_setWritableAndNonReplicating() {
        assertThatThrownBy(() -> ReplicationStatus.of(true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replicating must be true if writable is true");
    }

    @Test
    void redundantUpdateStatus_Writable() {
        final AggregatedHttpResponse res = updateStatus(ReplicationStatus.WRITABLE, Scope.LOCAL);
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void updateStatus_leaveReadOnlyMode() {
        // Enter replication-only mode.
        updateStatus_setUnwritable();
        // Try to enter writable mode.
        final AggregatedHttpResponse res = updateStatus(ReplicationStatus.WRITABLE);
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("\"WRITABLE\"");
    }

    @Test
    void updateStatus_enableReplicatingWithReadOnlyMode() {
        // Try to enter read-only mode with replication disabled.
        AggregatedHttpResponse res = updateStatus(ReplicationStatus.READ_ONLY, Scope.LOCAL);
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo("\"READ_ONLY\"");

        // Try to enable replication.
        // Replication can be enabled with the local scope.
        res = updateStatus(ReplicationStatus.REPLICATION_ONLY, Scope.LOCAL);
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo("\"REPLICATION_ONLY\"");
    }

    @Test
    void updateStatus_disableReplicatingWithReadOnlyMode() {
        // Try to enter replication-only mode.
        AggregatedHttpResponse res = updateStatus(ReplicationStatus.REPLICATION_ONLY);
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("\"REPLICATION_ONLY\"");

        // Try to disable replication.
        res = updateStatus(ReplicationStatus.READ_ONLY);
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("\"READ_ONLY\"");
    }

    AggregatedHttpResponse updateStatus(ReplicationStatus serverStatus) {
       return updateStatus(serverStatus, Scope.ALL);
    }

    AggregatedHttpResponse updateStatus(ReplicationStatus serverStatus, Scope scope) {
        final BlockingWebClient client = dogma.httpClient().blocking();
        return client.prepare()
                     .put(API_V1_PATH_PREFIX + "status")
                     .contentJson(new UpdateServerStatusRequest(serverStatus, scope))
                     .execute();
    }
}
