/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.centraldogma.server.internal.api.converter;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class RawContentConverterTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
        }
    };

    @Test
    void shouldConvertRawContent() {
        final ObjectNode commitNode = JsonNodeFactory.instance.objectNode();
        commitNode.set("commitMessage",
                       JsonNodeFactory.instance.objectNode()
                                               .put("summary", "test commit with raw content")
                                               .put("detail", "This is a detailed description.")
                                               .put("markup", Markup.PLAINTEXT.name()));
        final ObjectNode change =
                JsonNodeFactory.instance.objectNode()
                                        .put("path", "/test.json")
                                        .put("type", ChangeType.UPSERT_JSON.name())
                                        .put("rawContent", "{ \"key\": \"value\" }");
        commitNode.set("changes", JsonNodeFactory.instance.arrayNode().add(change));

        final BlockingWebClient client = dogma.blockingHttpClient();
        final AggregatedHttpResponse response = client.prepare()
                                                      .post("/api/v1/projects/foo/repos/bar/contents")
                                                      .contentJson(commitNode)
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        final Entry<JsonNode> entry =
                (Entry<JsonNode>) dogma.client()
                                       .forRepo("foo", "bar")
                                       .file("/test.json")
                                       .get()
                                       .join();
        assertThat(entry.path()).isEqualTo("/test.json");
        assertThat(entry.type()).isEqualTo(EntryType.JSON);
        assertThatJson(entry.content())
                .isEqualTo("{ \"key\": \"value\" }");
    }
}
