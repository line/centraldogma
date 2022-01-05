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
package com.linecorp.centraldogma.client.armeria;

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Json5;

class ArmeriaCentralDogmaTest {

    private static final String JSON5_STRING =
            "{\n" +
            "  // comments\n" +
            "  unquoted: 'and you can quote me on that',\n" +
            "  singleQuotes: 'I can use \"double quotes\" here',\n" +
            "  lineBreaks: \"Look, Mom! \\\n" +
            "No \\\\n's!\"," +
            "  leadingDecimalPoint: .8675309," +
            "  trailingComma: 'in objects', andIn: ['arrays',]," +
            "  \"backwardsCompatible\": \"with JSON\",\n" +
            "}\n";

    @Mock
    private WebClient webClient;

    private CentralDogma client;

    static <T> Entry<T> getFile(CentralDogma client, Query<T> query) {
        return client.getFile("foo", "bar", Revision.INIT, query).join();
    }

    static <T> Entry<T> watchFile(CentralDogma client, Query<T> query) {
        return client.watchFile("foo", "bar", Revision.INIT, query).join();
    }

    static void validateJson5Entry(Entry<?> entry) throws JsonParseException {
        assertThat(entry.path()).isEqualTo("/foo.json5");
        assertThat(entry.content()).isEqualTo(Json5.readTree(JSON5_STRING));
        assertThat(entry.contentAsText()).isEqualTo(JSON5_STRING);
    }

    @BeforeEach
    void setUp() {
        client = new ArmeriaCentralDogma(newSingleThreadScheduledExecutor(), webClient, "access_token");
    }

    @Test
    void testGetJson5File() throws Exception {
        when(webClient.execute(ArgumentMatchers.<RequestHeaders>any())).thenReturn(
                HttpResponse.ofJson(new MockEntryDto("/foo.json5", "JSON", JSON5_STRING)));

        final Entry<?> entry = getFile(client, Query.ofJson("/foo.json5"));
        validateJson5Entry(entry);
    }

    @Test
    void testGetJson5FileWithJsonPath() throws Exception {
        final JsonNode node = Jackson.readTree("{\"a\": \"bar\"}");
        when(webClient.execute(ArgumentMatchers.<RequestHeaders>any())).thenReturn(
                HttpResponse.ofJson(new MockEntryDto("/foo.json5", "JSON", node)));

        final Entry<?> entry = getFile(client, Query.ofJsonPath("/foo.json5", "$.a"));
        assertThat(entry.content()).isEqualTo(node);
    }

    @Test
    void testWatchJson5File() throws Exception {
        final MockEntryDto entryDto = new MockEntryDto("/foo.json5", "JSON", JSON5_STRING);
        when(webClient.execute(ArgumentMatchers.<RequestHeaders>any())).thenReturn(
                HttpResponse.ofJson(new MockWatchResultDto(1, entryDto)));

        final Entry<?> entry = watchFile(client, Query.ofJson("/foo.json5"));
        validateJson5Entry(entry);
    }

    static class MockEntryDto {

        private final String path;
        private final String type;
        private final Object content;

        MockEntryDto(String path, String type, Object content) {
            this.path = path;
            this.type = type;
            this.content = content;
        }

        @JsonProperty("path")
        String path() {
            return path;
        }

        @JsonProperty("type")
        String type() {
            return type;
        }

        @JsonProperty("content")
        Object content() {
            return content;
        }
    }

    static class MockWatchResultDto {

        private final int revision;
        private final MockEntryDto entry;

        MockWatchResultDto(int revision, MockEntryDto entry) {
            this.revision = revision;
            this.entry = entry;
        }

        @JsonProperty("revision")
        int revision() {
            return revision;
        }

        @JsonProperty("entry")
        MockEntryDto entry() {
            return entry;
        }
    }
}
