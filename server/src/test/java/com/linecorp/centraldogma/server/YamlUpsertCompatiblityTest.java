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

package com.linecorp.centraldogma.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;

class YamlUpsertCompatiblityTest {

    @RegisterExtension
    static final CentralDogmaReplicationExtension dogma = new CentralDogmaReplicationExtension(3) {
    };

    @BeforeAll
    static void beforeAll() {
        final CentralDogma client = dogma.servers().get(0).client();
        client.createProject("foo").join();
        client.createRepository("foo", "bar").join();
    }

    @Test
    void shouldAllowYamlUpsert() {
        final BlockingWebClient client0 = dogma.servers().get(0).blockingHttpClient();
        //language=JSON
        final String data = '{' +
                            "  \"commitMessage\": { " +
                            "    \"summary\": \"Add config.yaml\"," +
                            "    \"detail\": \"test yaml\"" +
                            "  }, " +
                            "  \"changes\": [" +
                            "    {" +
                            "      \"path\": \"/config.yaml\"," +
                            "      \"type\": \"UPSERT_YAML\"," +
                            "      \"content\": \"key: value\"" +
                            "    }" +
                            "  ]" +
                            '}';
        final ResponseEntity<PushResultDto> response =
                client0.prepare()
                       .post("/api/v1/projects/foo/repos/bar/contents")
                       .content(MediaType.JSON, data)
                       .asJson(PushResultDto.class)
                       .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().revision().major()).isEqualTo(2);
        final CentralDogma client1 = dogma.servers().get(0).client();
        final CentralDogmaRepository repo1 = client1.forRepo("foo", "bar");
        final Entry<String> replicated = repo1.file(Query.ofText("/config.yaml"))
                                              .get(new Revision(2))
                                              .join();
        assertThat(replicated.type()).isEqualTo(EntryType.TEXT);
        assertThat(replicated.content()).isEqualTo("key: value\n");
    }
}
