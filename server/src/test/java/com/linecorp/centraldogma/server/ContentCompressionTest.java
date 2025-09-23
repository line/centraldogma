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
package com.linecorp.centraldogma.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;
import com.google.common.io.CharStreams;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

/**
 * Makes sure an API response is compressed if a client requested with an 'accept-encoding' header.
 */
class ContentCompressionTest {

    private static final String PROJ = "proj";
    private static final String REPO = "repo";
    private static final String PATH = "/foo.txt";
    private static final String CONTENT = Strings.repeat("Central Dogma ", 1024);

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(PROJ).join();
            client.createRepository(PROJ, REPO)
                  .join()
                  .commit("Create a large file.", Change.ofTextUpsert(PATH, CONTENT))
                  .push().join();
        }
    };

    @Test
    void http() throws Exception {
        final WebClient client =
                WebClient.builder("http://127.0.0.1:" + dogma.serverAddress().getPort())
                         .setHeader(HttpHeaderNames.AUTHORIZATION, "Bearer " + CsrfToken.ANONYMOUS)
                         .setHeader(HttpHeaderNames.ACCEPT_ENCODING, "deflate")
                         .build();

        final String contentPath = HttpApiV1Constants.PROJECTS_PREFIX + '/' + PROJ +
                                   HttpApiV1Constants.REPOS + '/' + REPO +
                                   "/contents" + PATH;

        final AggregatedHttpResponse compressedResponse = client.get(contentPath).aggregate().join();
        assertThat(compressedResponse.status()).isEqualTo(HttpStatus.OK);

        final HttpData content = compressedResponse.content();
        try (Reader in = new InputStreamReader(new InflaterInputStream(new ByteArrayInputStream(
                content.array(), 0, content.length())), StandardCharsets.UTF_8)) {

            assertThat(CharStreams.toString(in)).contains(CONTENT);
        }
    }
}
