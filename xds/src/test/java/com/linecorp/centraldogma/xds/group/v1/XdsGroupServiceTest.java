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
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroupAsync;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.deleteGroup;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

final class XdsGroupServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            // To see if it's working when the web app is enabled.
            // When webAppEnabled is true, we add additional services that might affect service bind path.
            // https://github.com/line/centraldogma/blob/a4e58931ac98e8b6e9e470033ba04ee60180b135/server/src/main/java/com/linecorp/centraldogma/server/CentralDogma.java#L863
            builder.webAppEnabled(true);
        }
    };

    @Test
    void createGroupViaHttp() {
        AggregatedHttpResponse response = createGroupAsync("foo", dogma.httpClient()).join();
        assertOk(response);

        // Cannot create with the same name.
        response = createGroupAsync("foo", dogma.httpClient()).join();
        assertThat(response.status()).isSameAs(HttpStatus.CONFLICT);

        // Cannot create a group with an internal repository name.
        response = createGroupAsync("dogma", dogma.httpClient()).join();
        assertThat(response.status()).isSameAs(HttpStatus.FORBIDDEN);
        response = createGroupAsync("meta", dogma.httpClient()).join();
        assertThat(response.status()).isSameAs(HttpStatus.FORBIDDEN);

        // Cannot create a group with an invalid ID format.
        response = createGroupAsync("@invalid!", dogma.httpClient()).join();
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);
    }

    private static void assertOk(AggregatedHttpResponse response) {
        assertThat(response.status()).isSameAs(HttpStatus.OK);
    }

    private static void assertNoContent(AggregatedHttpResponse response) {
        assertThat(response.status()).isSameAs(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteGroupViaHttp() {
        AggregatedHttpResponse response = deleteGroup("groups/bar", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createGroup("bar", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        response = deleteGroup("groups/bar", dogma.httpClient());
        assertNoContent(response);
    }
}
