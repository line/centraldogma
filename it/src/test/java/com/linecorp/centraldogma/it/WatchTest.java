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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Latest;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

class WatchTest {

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding();

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchRepository(ClientType clientType) throws Exception {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final Revision rev1 = client.normalizeRevision(dogma.project(), dogma.repo1(), Revision.HEAD).join();

        final CompletableFuture<Revision> future =
                client.watchRepository(dogma.project(), dogma.repo1(), rev1, "/**", 3000);

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        final Change<JsonNode> change = Change.ofJsonUpsert("/test/test3.json",
                                                            "[" + System.currentTimeMillis() + ", " +
                                                            System.nanoTime() + ']');

        final PushResult result = client.push(
                dogma.project(), dogma.repo1(), rev1, "Add test3.json", change).join();

        final Revision rev2 = result.revision();

        assertThat(rev2).isEqualTo(rev1.forward(1));
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(rev2);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchRepositoryImmediateWakeup(ClientType clientType) throws Exception {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final Revision rev1 = client.normalizeRevision(dogma.project(), dogma.repo1(), Revision.HEAD).join();
        final Change<JsonNode> change = Change.ofJsonUpsert("/test/test3.json",
                                                            "[" + System.currentTimeMillis() + ", " +
                                                            System.nanoTime() + ']');

        final PushResult result = client.push(
                dogma.project(), dogma.repo1(), rev1, "Add test3.json", change).join();

        final Revision rev2 = result.revision();

        assertThat(rev2).isEqualTo(rev1.forward(1));

        final CompletableFuture<Revision> future =
                client.watchRepository(dogma.project(), dogma.repo1(), rev1, "/**", 3000);
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(rev2);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchRepositoryWithUnrelatedChange(ClientType clientType) throws Exception {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final Revision rev0 = client.normalizeRevision(dogma.project(), dogma.repo1(), Revision.HEAD).join();
        final CompletableFuture<Revision> future =
                client.watchRepository(dogma.project(), dogma.repo1(), rev0, "/test/test4.json", 3000);

        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test3.json",
                                                             "[" + System.currentTimeMillis() + ", " +
                                                             System.nanoTime() + ']');
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test4.json",
                                                             "[" + System.currentTimeMillis() + ", " +
                                                             System.nanoTime() + ']');

        final PushResult result1 = client.push(
                dogma.project(), dogma.repo1(), rev0, "Add test3.json", change1).join();
        final Revision rev1 = result1.revision();
        assertThat(rev1).isEqualTo(rev0.forward(1));

        // Ensure that the watcher is not notified because the path pattern does not match test3.json.
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        final PushResult result2 = client.push(
                dogma.project(), dogma.repo1(), rev1, "Add test4.json", change2).join();
        final Revision rev2 = result2.revision();
        assertThat(rev2).isEqualTo(rev1.forward(1));

        // Now it should be notified.
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(rev2);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchRepositoryTimeout(ClientType clientType) {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final Revision rev = client.watchRepository(
                dogma.project(), dogma.repo1(), Revision.HEAD, "/**", 1000).join();
        assertThat(rev).isNull();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchFile(ClientType clientType) throws Exception {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final Revision rev0 = client
                .normalizeRevision(dogma.project(), dogma.repo1(), Revision.HEAD)
                .join();

        final CompletableFuture<Entry<JsonNode>> future =
                client.watchFile(dogma.project(), dogma.repo1(), rev0,
                                 Query.ofJsonPath("/test/test1.json", "$[0]"), 3000);

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // An irrelevant change should not trigger a notification.
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test2.json", "[ 3, 2, 1 ]");

        final PushResult res1 = client.push(
                dogma.project(), dogma.repo1(), rev0, "Add test2.json", change1).join();

        final Revision rev1 = res1.revision();

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // Make a relevant change now.
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test1.json", "[ -1, -2, -3 ]");

        final PushResult res2 = client.push(
                dogma.project(), dogma.repo1(), rev1, "Add test1.json", change2).join();

        final Revision rev2 = res2.revision();

        assertThat(rev2).isEqualTo(rev0.forward(2));
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(
                Entry.ofJson(rev2, "/test/test1.json", "-1"));
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchFileWithIdentityQuery(ClientType clientType) throws Exception {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final Revision rev0 = client
                .normalizeRevision(dogma.project(), dogma.repo1(), Revision.HEAD)
                .join();

        final CompletableFuture<Entry<JsonNode>> future = client.watchFile(
                dogma.project(), dogma.repo1(), rev0,
                Query.ofJson("/test/test1.json"), 3000);

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // An irrelevant change should not trigger a notification.
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test2.json", "[ 3, 2, 1 ]");

        final PushResult res1 = client.push(
                dogma.project(), dogma.repo1(), rev0, "Add test2.json", change1).join();

        final Revision rev1 = res1.revision();

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // Make a relevant change now.
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test1.json", "[ -1, -2, -3 ]");

        final PushResult res2 = client.push(
                dogma.project(), dogma.repo1(), rev1, "Update test1.json", change2).join();

        final Revision rev2 = res2.revision();

        assertThat(rev2).isEqualTo(rev0.forward(2));
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(
                Entry.ofJson(rev2, "/test/test1.json", "[-1,-2,-3]"));
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchFileWithTimeout(ClientType clientType) {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final Entry<JsonNode> res = client.watchFile(
                dogma.project(), dogma.repo1(), Revision.HEAD,
                Query.ofJsonPath("/test/test1.json", "$"), 1000).join();

        assertThat(res).isNull();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchJsonAsText(ClientType clientType) throws InterruptedException {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final Watcher<JsonNode> jsonWatcher = client.fileWatcher(dogma.project(), dogma.repo1(),
                                                                 Query.ofJson("/test/test2.json"));
        assertThatJson(jsonWatcher.awaitInitialValue().value()).isEqualTo("{\"a\":\"apple\"}");

        final Watcher<String> stringWatcher = client.fileWatcher(dogma.project(), dogma.repo1(),
                                                                 Query.ofText("/test/test2.json"));
        assertThat(stringWatcher.awaitInitialValue().value()).isEqualTo("{\"a\":\"apple\"}");
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void transformingWatcher(ClientType clientType) throws InterruptedException {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final String filePath = "/test/test2.json";
        final Watcher<JsonNode> heavyWatcher = client.fileWatcher(dogma.project(), dogma.repo1(),
                                                                  Query.ofJsonPath(filePath));

        final Watcher<JsonNode> forExisting = Watcher.atJsonPointer(heavyWatcher, "/a");
        final AtomicReference<Latest<JsonNode>> watchResult = new AtomicReference<>();
        final AtomicInteger triggeredCount = new AtomicInteger();
        forExisting.watch((rev, node) -> {
            watchResult.set(new Latest<>(rev, node));
            triggeredCount.incrementAndGet();
        });

        // After the initial value is fetched, `latest` points to the specified JSON path
        final Latest<JsonNode> initialValue = forExisting.awaitInitialValue();

        final Revision rev0 = client
                .normalizeRevision(dogma.project(), dogma.repo1(), Revision.HEAD)
                .join();
        assertThat(initialValue.revision()).isEqualTo(rev0);
        assertThat(initialValue.value()).isEqualTo(new TextNode("apple"));
        assertThat(forExisting.latest()).isEqualTo(initialValue);
        assertThat(triggeredCount.get()).isEqualTo(1);
        assertThat(watchResult.get()).isEqualTo(initialValue);

        // An irrelevant change should not trigger a notification.
        final Change<JsonNode> unrelatedChange = Change.ofJsonUpsert(
                filePath, "{ \"a\": \"apple\", \"b\": \"banana\" }");
        final Revision rev1 = client.push(dogma.project(), dogma.repo1(), rev0, "Add /b", unrelatedChange)
                                    .join()
                                    .revision();

        assertThat(triggeredCount.get()).isEqualTo(1);
        assertThat(watchResult.get()).isEqualTo(initialValue);

        // An relevant change should trigger a notification.
        final Change<JsonNode> relatedChange = Change.ofJsonUpsert(
                filePath, "{ \"a\": \"artichoke\", \"b\": \"banana\" }");
        final Revision rev2 = client.push(dogma.project(), dogma.repo1(), rev1, "Change /a", relatedChange)
                                    .join()
                                    .revision();

        await().untilAsserted(() -> assertThat(forExisting.latest()).isEqualTo(
                new Latest<>(rev2, new TextNode("artichoke"))));
        assertThat(watchResult.get()).isEqualTo(forExisting.latest());
        assertThat(triggeredCount.get()).isEqualTo(2);

        // Once closed, it's deaf
        forExisting.close();

        final Change<JsonNode> nextRelatedChange = Change.ofJsonUpsert(
                filePath, "{ \"a\": \"apricot\", \"b\": \"banana\" }");
        final Revision rev3 = client.push(dogma.project(), dogma.repo1(), rev2, "Change /a again",
                                          nextRelatedChange)
                                    .join()
                                    .revision();

        Thread.sleep(1100); // DELAY_ON_SUCCESS_MILLIS + epsilon
        assertThat(forExisting.latest()).isEqualTo(new Latest<>(rev2, new TextNode("artichoke")));
        assertThat(watchResult.get()).isEqualTo(forExisting.latest());
        assertThat(triggeredCount.get()).isEqualTo(2);
        assertThat(heavyWatcher.latestValue().at("/a")).isEqualTo(new TextNode("apricot"));
        assertThat(heavyWatcher.latest().revision()).isEqualTo(rev3);
    }

    private static void revertTestFiles(ClientType clientType) {
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test1.json", "[ 1, 2, 3 ]");
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test2.json", "{ \"a\": \"apple\" }");

        final List<Change<JsonNode>> changes = Arrays.asList(change1, change2);
        final CentralDogma client = clientType.client(dogma);

        if (!client.getPreviewDiffs(dogma.project(), dogma.repo1(), Revision.HEAD, changes)
                   .join().isEmpty()) {
            client.push(dogma.project(), dogma.repo1(), Revision.HEAD,
                        "Revert test files", changes).join();
        }
    }
}
