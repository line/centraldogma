/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.centraldogma.server.internal.api.ContentServiceV1Test.CONTENTS_PREFIX;
import static com.linecorp.centraldogma.server.internal.api.ContentServiceV1Test.addFooJson;
import static com.linecorp.centraldogma.server.internal.api.ContentServiceV1Test.createProject;
import static com.linecorp.centraldogma.server.internal.api.ContentServiceV1Test.editFooJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ContentServiceV1WatchTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        }

        @Override
        protected void scaffold(CentralDogma client) {
            createProject(dogma);
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void watchFile_notifyEntryNotFound() {
        watch_notifyEntryNotFound("/foo.json", false);
    }

    @Test
    void watchRepository_notifyEntryNotFound() {
        watch_notifyEntryNotFound("/**", true);
    }

    private void watch_notifyEntryNotFound(String path, boolean repository) {
        final WebClient client = dogma.httpClient();
        sendWatchRequest(client, path, 1, HttpStatus.NOT_FOUND, "EntryNotFoundException");

        AggregatedHttpResponse res = addFooJson(client); // Revision 2
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        sendWatchRequest(client, path, 1, HttpStatus.OK, "\"revision\":2");
        removeFooJson(client); // Revision 3
        sendWatchRequest(client, path, 1, HttpStatus.NOT_FOUND, "EntryNotFoundException");
        if (repository) {
            // watch repository gets the revision when the entry is removed.
            // The client can send the subsequent getFiles request to notice that the file is removed.
            sendWatchRequest(client, path, 2, HttpStatus.OK, "\"revision\":3");
        } else {
            sendWatchRequest(client, path, 2, HttpStatus.NOT_FOUND, "EntryNotFoundException");
        }
        sendWatchRequest(client, path, 3, HttpStatus.NOT_FOUND, "EntryNotFoundException");

        res = addFooJson(client); // Revision 4
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        sendWatchRequest(client, path, 1, HttpStatus.OK, "\"revision\":4");
        sendWatchRequest(client, path, 3, HttpStatus.OK, "\"revision\":4");

        final CompletableFuture<AggregatedHttpResponse> future =
                watchRequest(client, path, 2).aggregate();
        // Revision 2 has the same content with the revision 4 so it waits until foo.json is changed.
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                .isExactlyInstanceOf(TimeoutException.class);

        res = editFooJson(client); // Revision 5
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        res = future.join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).contains("\"revision\":5");
    }

    private static void removeFooJson(WebClient client) {
        final String body =
                '{' +
                "   \"path\": \"/foo.json\"," +
                "   \"type\": \"REMOVE\"," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Delete foo.json\"" +
                "   }" +
                '}';
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final AggregatedHttpResponse join = client.execute(headers, body).aggregate().join();
        assertThat(join.status()).isSameAs(HttpStatus.OK);
    }

    private static void sendWatchRequest(WebClient client, String path, int revision,
                                         HttpStatus expectedStatus, String expectedContent) {
        final AggregatedHttpResponse res = watchRequest(client, path, revision).aggregate().join();
        assertThat(res.status()).isSameAs(expectedStatus);
        assertThat(res.contentUtf8()).contains(expectedContent);
    }

    private static HttpResponse watchRequest(WebClient client, String path, int revision) {
        return client.prepare()
                     .get(CONTENTS_PREFIX + path)
                     .header(HttpHeaderNames.IF_NONE_MATCH, revision)
                     .header(HttpHeaderNames.PREFER, "wait=100, notify-entry-not-found=true")
                     .execute();
    }
}
