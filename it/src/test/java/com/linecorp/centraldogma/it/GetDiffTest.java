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

package com.linecorp.centraldogma.it;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

class GetDiffTest {

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding();

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void queryJsonByRange(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final String path = "/test_json_file.json";
        for (int i = 0; i < 5; i++) {
            final Change<JsonNode> change = Change.ofJsonUpsert(path, String.format("{ \"key\" : \"%d\"}", i));
            client.forRepo(dogma.project(), dogma.repo1())
                  .commit(TestConstants.randomText(), change)
                  .push().join();
        }

        final Change<JsonNode> res = client.getDiff(
                dogma.project(), dogma.repo1(),
                new Revision(-4), new Revision(-1),
                Query.ofJsonPath(path, "$.key")).join();

        assertThat(res.type()).isEqualTo(ChangeType.APPLY_JSON_PATCH);

        assertThatJson(res.content()).isEqualTo(
                "[{" +
                "  \"op\": \"safeReplace\"," +
                "  \"path\": \"\"," +
                "  \"oldValue\": \"1\"," +
                "  \"value\": \"4\"" +
                "}]");
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void queryYamlByRange(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final String path = "/test_yaml_file.yml";
        for (int i = 0; i < 5; i++) {
            final Change<JsonNode> change = Change.ofYamlUpsert(path, String.format("key: %d\n", i));
            client.forRepo(dogma.project(), dogma.repo1())
                  .commit(TestConstants.randomText(), change)
                  .push().join();
        }

        final Change<JsonNode> res = client.getDiff(
                dogma.project(), dogma.repo1(),
                new Revision(-4), new Revision(-1),
                Query.ofJsonPath(path, "$.key")).join();

        assertThat(res.type()).isEqualTo(ChangeType.APPLY_YAML_PATCH);

        assertThatJson(res.content()).isEqualTo(
                "[{" +
                "  \"op\": \"safeReplace\"," +
                "  \"path\": \"\"," +
                "  \"oldValue\": 1," +
                "  \"value\": 4" +
                "}]");
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void diff_remove(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);

        final Revision rev1 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("summary1", Change.ofTextUpsert("/foo.txt", "hello"))
                                    .push()
                                    .join()
                                    .revision();

        final Revision rev2 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("summary2", Change.ofRemoval("/foo.txt"))
                                    .push()
                                    .join()
                                    .revision();

        assertThat(rev1.forward(1)).isEqualTo(rev2);

        assertThat(client.getDiff(dogma.project(), dogma.repo1(), rev1, rev1,
                                  Query.ofText("/foo.txt")).join().type())
                .isEqualTo(ChangeType.APPLY_TEXT_PATCH);

        assertThat(client.getDiff(dogma.project(), dogma.repo1(), rev1, rev2,
                                  Query.ofText("/foo.txt")).join())
                .isEqualTo(Change.ofRemoval("/foo.txt"));
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void diff_rename(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);

        try {
            final Revision rev1 = client.forRepo(dogma.project(), dogma.repo1())
                                        .commit("summary1", Change.ofTextUpsert("/bar.txt", "hello"))
                                        .push()
                                        .join()
                                        .revision();

            final Revision rev2 = client.forRepo(dogma.project(), dogma.repo1())
                                        .commit("summary2", Change.ofRename("/bar.txt", "/baz.txt"))
                                        .push()
                                        .join()
                                        .revision();

            assertThat(rev1.forward(1)).isEqualTo(rev2);

            assertThat(client.getDiff(dogma.project(), dogma.repo1(), rev1, rev2,
                                      Query.ofText("/bar.txt")).join())
                    .isEqualTo(Change.ofRemoval("/bar.txt"));
        } finally {
            client.forRepo(dogma.project(), dogma.repo1())
                  .commit("summary3", Change.ofRemoval("/baz.txt"))
                  .push()
                  .join();
        }
    }
}
