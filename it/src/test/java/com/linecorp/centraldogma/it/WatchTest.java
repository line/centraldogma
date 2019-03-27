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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

public class WatchTest extends AbstractMultiClientTest {

    @ClassRule
    public static final CentralDogmaRuleWithScaffolding rule = new CentralDogmaRuleWithScaffolding();

    public WatchTest(ClientType clientType) {
        super(clientType);
    }

    @Before
    public void revertTestFiles() {
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test1.json", "[ 1, 2, 3 ]");
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test2.json", "{ \"a\": \"apple\" }");

        final List<Change<JsonNode>> changes = Arrays.asList(change1, change2);
        if (!client()
                 .getPreviewDiffs(rule.project(), rule.repo1(), Revision.HEAD, changes)
                 .join().isEmpty()) {
            client().push(
                    rule.project(), rule.repo1(), Revision.HEAD,
                    "Revert test files", changes).join();
        }
    }

    @Test
    public void testWatchRepository() throws Exception {
        final Revision rev1 = client().normalizeRevision(rule.project(), rule.repo1(), Revision.HEAD).join();

        final CompletableFuture<Revision> future =
                client().watchRepository(rule.project(), rule.repo1(), rev1, "/**", 3000);

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        final Change<JsonNode> change = Change.ofJsonUpsert("/test/test3.json",
                                                            "[" + System.currentTimeMillis() + ", " +
                                                            System.nanoTime() + ']');

        final PushResult result = client().push(
                rule.project(), rule.repo1(), rev1, "Add test3.json", change).join();

        final Revision rev2 = result.revision();

        assertThat(rev2).isEqualTo(rev1.forward(1));
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(rev2);
    }

    @Test
    public void testWatchRepositoryImmediateWakeup() throws Exception {
        final Revision rev1 = client().normalizeRevision(rule.project(), rule.repo1(), Revision.HEAD).join();
        final Change<JsonNode> change = Change.ofJsonUpsert("/test/test3.json",
                                                            "[" + System.currentTimeMillis() + ", " +
                                                            System.nanoTime() + ']');

        final PushResult result = client().push(
                rule.project(), rule.repo1(), rev1, "Add test3.json", change).join();

        final Revision rev2 = result.revision();

        assertThat(rev2).isEqualTo(rev1.forward(1));

        final CompletableFuture<Revision> future =
                client().watchRepository(rule.project(), rule.repo1(), rev1, "/**", 3000);
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(rev2);
    }

    @Test
    public void testWatchRepositoryWithUnrelatedChange() throws Exception {
        final Revision rev0 = client().normalizeRevision(rule.project(), rule.repo1(), Revision.HEAD).join();
        final CompletableFuture<Revision> future =
                client().watchRepository(rule.project(), rule.repo1(), rev0, "/test/test4.json", 3000);

        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test3.json",
                                                             "[" + System.currentTimeMillis() + ", " +
                                                             System.nanoTime() + ']');
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test4.json",
                                                             "[" + System.currentTimeMillis() + ", " +
                                                             System.nanoTime() + ']');

        final PushResult result1 = client().push(
                rule.project(), rule.repo1(), rev0, "Add test3.json", change1).join();
        final Revision rev1 = result1.revision();
        assertThat(rev1).isEqualTo(rev0.forward(1));

        // Ensure that the watcher is not notified because the path pattern does not match test3.json.
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        final PushResult result2 = client().push(
                rule.project(), rule.repo1(), rev1, "Add test4.json", change2).join();
        final Revision rev2 = result2.revision();
        assertThat(rev2).isEqualTo(rev1.forward(1));

        // Now it should be notified.
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(rev2);
    }

    @Test
    public void testWatchRepositoryTimeout() {
        final Revision rev = client().watchRepository(
                rule.project(), rule.repo1(), Revision.HEAD, "/**", 1000).join();
        assertThat(rev).isNull();
    }

    @Test
    public void testWatchFile() throws Exception {
        final Revision rev0 = client()
                                  .normalizeRevision(rule.project(), rule.repo1(), Revision.HEAD)
                                  .join();

        final CompletableFuture<Entry<JsonNode>> future =
                client().watchFile(rule.project(), rule.repo1(), rev0,
                                        Query.ofJsonPath("/test/test1.json", "$[0]"), 3000);

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // An irrelevant change should not trigger a notification.
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test2.json", "[ 3, 2, 1 ]");

        final PushResult res1 = client().push(
                rule.project(), rule.repo1(), rev0, "Add test2.json", change1).join();

        final Revision rev1 = res1.revision();

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // Make a relevant change now.
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test1.json", "[ -1, -2, -3 ]");

        final PushResult res2 = client().push(
                rule.project(), rule.repo1(), rev1, "Add test1.json", change2).join();

        final Revision rev2 = res2.revision();

        assertThat(rev2).isEqualTo(rev0.forward(2));
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(
                Entry.ofJson(rev2, "/test/test1.json", "-1"));
    }

    @Test
    public void testWatchFileWithIdentityQuery() throws Exception {
        final Revision rev0 = client()
                                  .normalizeRevision(rule.project(), rule.repo1(), Revision.HEAD)
                                  .join();

        final CompletableFuture<Entry<JsonNode>> future = client().watchFile(
                rule.project(), rule.repo1(), rev0,
                Query.ofJson("/test/test1.json"), 3000);

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // An irrelevant change should not trigger a notification.
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test2.json", "[ 3, 2, 1 ]");

        final PushResult res1 = client().push(
                rule.project(), rule.repo1(), rev0, "Add test2.json", change1).join();

        final Revision rev1 = res1.revision();

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // Make a relevant change now.
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test1.json", "[ -1, -2, -3 ]");

        final PushResult res2 = client().push(
                rule.project(), rule.repo1(), rev1, "Update test1.json", change2).join();

        final Revision rev2 = res2.revision();

        assertThat(rev2).isEqualTo(rev0.forward(2));
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(
                Entry.ofJson(rev2, "/test/test1.json", "[-1,-2,-3]"));
    }

    @Test
    public void testWatchFileWithTimeout() {
        final Entry<JsonNode> res = client().watchFile(
                rule.project(), rule.repo1(), Revision.HEAD,
                Query.ofJsonPath("/test/test1.json", "$"), 1000).join();

        assertThat(res).isNull();
    }
}
