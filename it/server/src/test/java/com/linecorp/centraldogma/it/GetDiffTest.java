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

import java.util.List;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

class GetDiffTest {

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding();

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void queryByRange(ClientType clientType) {
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
    void diff_yamlAdd(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final String yamlPath = "/test_yaml_add.yaml";
        final String yamlContent = "key: value\nlist:\n  - item1\n  - item2\n";

        final Revision rev1 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Add YAML", Change.ofYamlUpsert(yamlPath, yamlContent))
                                    .push().join().revision();

        // Verify diff from init to current shows the YAML add.
        final List<Change<?>> diffs = client.forRepo(dogma.project(), dogma.repo1())
                                            .diff(PathPattern.of(yamlPath))
                                            .get(rev1.backward(1), rev1).join();
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).path()).isEqualTo(yamlPath);
        assertThat(diffs.get(0).type()).isEqualTo(ChangeType.UPSERT_YAML);
        assertThatJson(diffs.get(0).content()).isEqualTo(
                "{" +
                "  \"key\": \"value\"," +
                "  \"list\": [\"item1\", \"item2\"]" +
                "}");
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void diff_yamlModify(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final String yamlPath = "/test_yaml_modify.yml";

        final Revision rev1 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Add YAML", Change.ofYamlUpsert(yamlPath, "name: foo\n"))
                                    .push().join().revision();

        final Revision rev2 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Update YAML", Change.ofYamlUpsert(yamlPath, "name: bar\n"))
                                    .push().join().revision();

        // Path-pattern diff: YAML is diffed as JSON.
        final List<Change<?>> diffs = client.forRepo(dogma.project(), dogma.repo1())
                                            .diff(PathPattern.of(yamlPath))
                                            .get(rev1, rev2).join();
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).path()).isEqualTo(yamlPath);
        assertThat(diffs.get(0).type()).isEqualTo(ChangeType.APPLY_JSON_PATCH);
        assertThatJson(diffs.get(0).content()).isEqualTo(
                "[{" +
                "  \"op\": \"safeReplace\"," +
                "  \"path\": \"/name\"," +
                "  \"oldValue\": \"foo\"," +
                "  \"value\": \"bar\"" +
                "}]");

        // Query-based diff: YAML query returns a JSON patch.
        final Change<?> yamlDiff = client.getDiff(dogma.project(), dogma.repo1(), rev1, rev2,
                                                   Query.ofYaml(yamlPath)).join();
        assertThat(yamlDiff.path()).isEqualTo(yamlPath);
        assertThat(yamlDiff.type()).isEqualTo(ChangeType.APPLY_JSON_PATCH);
        assertThatJson(yamlDiff.content()).isEqualTo(
                "[{" +
                "  \"op\": \"safeReplace\"," +
                "  \"path\": \"/name\"," +
                "  \"oldValue\": \"foo\"," +
                "  \"value\": \"bar\"" +
                "}]");
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void diff_yamlRemove(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final String yamlPath = "/test_yaml_remove.yaml";

        final Revision rev1 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Add YAML", Change.ofYamlUpsert(yamlPath, "data: test\n"))
                                    .push().join().revision();

        final Revision rev2 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Remove YAML", Change.ofRemoval(yamlPath))
                                    .push().join().revision();

        final List<Change<?>> diffs = client.forRepo(dogma.project(), dogma.repo1())
                                            .diff(PathPattern.of(yamlPath))
                                            .get(rev1, rev2).join();
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).path()).isEqualTo(yamlPath);
        assertThat(diffs.get(0).type()).isEqualTo(ChangeType.REMOVE);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void diff_json5Modify(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final String json5Path = "/test_json5_modify.json5";

        final Revision rev1 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Add JSON5",
                                            Change.ofJsonUpsert(json5Path, "{ \"key\": \"value1\" }"))
                                    .push().join().revision();

        final Revision rev2 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Update JSON5",
                                            Change.ofJsonUpsert(json5Path, "{ \"key\": \"value2\" }"))
                                    .push().join().revision();

        // Path-pattern diff: JSON5 produces a JSON patch.
        final List<Change<?>> diffs = client.forRepo(dogma.project(), dogma.repo1())
                                            .diff(PathPattern.of(json5Path))
                                            .get(rev1, rev2).join();
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).path()).isEqualTo(json5Path);
        assertThat(diffs.get(0).type()).isEqualTo(ChangeType.APPLY_JSON_PATCH);
        assertThatJson(diffs.get(0).content()).isEqualTo(
                "[{" +
                "  \"op\": \"safeReplace\"," +
                "  \"path\": \"/key\"," +
                "  \"oldValue\": \"value1\"," +
                "  \"value\": \"value2\"" +
                "}]");

        // Query-based diff with JSON query.
        final Change<JsonNode> jsonDiff = client.getDiff(dogma.project(), dogma.repo1(), rev1, rev2,
                                                          Query.ofJson(json5Path)).join();
        assertThat(jsonDiff.type()).isEqualTo(ChangeType.APPLY_JSON_PATCH);
        assertThatJson(jsonDiff.content()).isEqualTo(
                "[{" +
                "  \"op\": \"safeReplace\"," +
                "  \"path\": \"/key\"," +
                "  \"oldValue\": \"value1\"," +
                "  \"value\": \"value2\"" +
                "}]");
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
