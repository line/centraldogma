/*
 * Copyright 2017 LINE Corporation
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

import static com.linecorp.centraldogma.common.Revision.HEAD;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

public class GetDiffTest extends AbstractMultiClientTest {

    @ClassRule
    public static final CentralDogmaRuleWithScaffolding rule = new CentralDogmaRuleWithScaffolding();

    @Rule
    public final TestName testName = new TestName();

    public GetDiffTest(ClientType clientType) {
        super(clientType);
    }

    @Test
    public void testQueryByRange() throws Exception {
        final String path = "/test_json_file.json";
        for (int i = 0; i < 5; i++) {
            final Change<JsonNode> change = Change.ofJsonUpsert(path, String.format("{ \"key\" : \"%d\"}", i));
            client().push(
                    rule.project(), rule.repo1(), HEAD,
                    TestConstants.randomText(), change).join();
        }

        final Change<JsonNode> res = client().getDiff(
                rule.project(), rule.repo1(),
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

    @Test
    public void testDiff_remove() throws Exception {
        final CentralDogma client = client();

        final Revision rev1 = client.push(rule.project(), rule.repo1(), HEAD, "summary1",
                                          Change.ofTextUpsert("/foo.txt", "hello")).join().revision();

        final Revision rev2 = client.push(rule.project(), rule.repo1(), HEAD, "summary2",
                                          Change.ofRemoval("/foo.txt")).join().revision();

        assertThat(rev1.forward(1)).isEqualTo(rev2);

        assertThat(client.getDiff(rule.project(), rule.repo1(), rev1, rev1,
                                  Query.ofText("/foo.txt")).join().type())
                .isEqualTo(ChangeType.APPLY_TEXT_PATCH);

        assertThat(client.getDiff(rule.project(), rule.repo1(), rev1, rev2,
                                  Query.ofText("/foo.txt")).join())
                .isEqualTo(Change.ofRemoval("/foo.txt"));
    }

    @Test
    public void testDiff_rename() throws Exception {
        final CentralDogma client = client();

        try {
            final Revision rev1 = client.push(rule.project(), rule.repo1(), HEAD, "summary1",
                                              Change.ofTextUpsert("/bar.txt", "hello")).join().revision();

            final Revision rev2 = client.push(rule.project(), rule.repo1(), HEAD, "summary2",
                                              Change.ofRename("/bar.txt", "/baz.txt")).join().revision();

            assertThat(rev1.forward(1)).isEqualTo(rev2);

            assertThat(client.getDiff(rule.project(), rule.repo1(), rev1, rev2,
                                      Query.ofText("/bar.txt")).join())
                    .isEqualTo(Change.ofRemoval("/bar.txt"));
        } finally {
            client.push(rule.project(), rule.repo1(), HEAD, "summary3", Change.ofRemoval("/baz.txt")).join();
        }
    }
}
