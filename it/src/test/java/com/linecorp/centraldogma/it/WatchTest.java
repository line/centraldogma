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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Latest;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.client.armeria.legacy.LegacyCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

class WatchTest {

    private static final String THREAD_NAME_PREFIX = "blocking-thread";
    private static final ScheduledExecutorService blockingTaskExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    ThreadFactories.newThreadFactory(THREAD_NAME_PREFIX, true));

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding() {
        @Override
        protected void configureClient(ArmeriaCentralDogmaBuilder builder) {
            builder.blockingTaskExecutor(blockingTaskExecutor);
        }

        @Override
        protected void configureClient(LegacyCentralDogmaBuilder builder) {
            builder.blockingTaskExecutor(blockingTaskExecutor);
        }
    };

    @AfterAll
    static void shutdownExecutor() {
        blockingTaskExecutor.shutdown();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchRepository(ClientType clientType) throws Exception {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final Revision rev1 = client.normalizeRevision(dogma.project(), dogma.repo1(), Revision.HEAD).join();

        final CompletableFuture<Revision> future =
                client.watchRepository(dogma.project(), dogma.repo1(), rev1, PathPattern.all(), 3000, false);

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        final Change<JsonNode> change = Change.ofJsonUpsert("/test/test3.json",
                                                            "[" + System.currentTimeMillis() + ", " +
                                                            System.nanoTime() + ']');

        final PushResult result = client.forRepo(dogma.project(), dogma.repo1())
                                        .commit("Add test3.json", change)
                                        .push(rev1)
                                        .join();

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

        final PushResult result = client.forRepo(dogma.project(), dogma.repo1())
                                        .commit("Add test3.json", change)
                                        .push(rev1)
                                        .join();

        final Revision rev2 = result.revision();

        assertThat(rev2).isEqualTo(rev1.forward(1));

        final CompletableFuture<Revision> future =
                client.watchRepository(dogma.project(), dogma.repo1(), rev1, PathPattern.all(), 3000, false);
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(rev2);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchRepositoryWithUnrelatedChange(ClientType clientType) throws Exception {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final Revision rev0 = client.normalizeRevision(dogma.project(), dogma.repo1(), Revision.HEAD).join();
        final CompletableFuture<Revision> future =
                client.watchRepository(dogma.project(), dogma.repo1(), rev0,
                                       PathPattern.of("/test/test4.json"), 3000, false);

        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test3.json",
                                                             "[" + System.currentTimeMillis() + ", " +
                                                             System.nanoTime() + ']');
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test4.json",
                                                             "[" + System.currentTimeMillis() + ", " +
                                                             System.nanoTime() + ']');

        final PushResult result1 = client.forRepo(dogma.project(), dogma.repo1())
                                         .commit("Add test3.json", change1)
                                         .push(rev0)
                                         .join();
        final Revision rev1 = result1.revision();
        assertThat(rev1).isEqualTo(rev0.forward(1));

        // Ensure that the watcher is not notified because the path pattern does not match test3.json.
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        final PushResult result2 = client.forRepo(dogma.project(), dogma.repo1())
                                         .commit("Add test4.json", change2)
                                         .push(rev1)
                                         .join();
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
                dogma.project(), dogma.repo1(), Revision.HEAD, PathPattern.all(), 1000, false).join();
        assertThat(rev).isNull();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchRepositoryWithNotExist(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        final CompletableFuture<Revision> future1 =
                client.watchRepository(dogma.project(), dogma.repo1(),
                                       Revision.HEAD, PathPattern.of("/test_not_found/**"),
                                       1000, false);
        assertThat(future1.join()).isNull();

        // Legacy client doesn't support this feature.
        if (clientType == ClientType.LEGACY) {
            return;
        }

        final CompletableFuture<Revision> future2 =
                client.watchRepository(dogma.project(), dogma.repo1(),
                                       Revision.HEAD, PathPattern.of("/test_not_found/**"),
                                       1000, true);
        assertThatThrownBy(future2::join).getCause().isInstanceOf(EntryNotFoundException.class);
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
                                 Query.ofJsonPath("/test/test1.json", "$[0]"), 3000, false);

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // An irrelevant change should not trigger a notification.
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test2.json", "[ 3, 2, 1 ]");

        final PushResult res1 = client.forRepo(dogma.project(), dogma.repo1())
                                      .commit("Add test2.json", change1)
                                      .push(rev0)
                                      .join();

        final Revision rev1 = res1.revision();

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // Make a relevant change now.
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test1.json", "[ -1, -2, -3 ]");

        final PushResult res2 = client.forRepo(dogma.project(), dogma.repo1())
                                      .commit("Add test1.json", change2)
                                      .push(rev1)
                                      .join();

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
                Query.ofJson("/test/test1.json"), 3000, false);

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // An irrelevant change should not trigger a notification.
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test2.json", "[ 3, 2, 1 ]");

        final PushResult res1 = client.forRepo(dogma.project(), dogma.repo1())
                                      .commit("Add test2.json", change1)
                                      .push(rev0)
                                      .join();

        final Revision rev1 = res1.revision();

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        // Make a relevant change now.
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test1.json", "[ -1, -2, -3 ]");

        final PushResult res2 = client.forRepo(dogma.project(), dogma.repo1())
                                      .commit("Update test1.json", change2)
                                      .push(rev1)
                                      .join();

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
                Query.ofJsonPath("/test/test1.json", "$"), 1000, false).join();

        assertThat(res).isNull();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchFileWithNotExistFile(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);

        final CompletableFuture<Entry<JsonNode>> future1 = client.watchFile(
                dogma.project(), dogma.repo1(), Revision.HEAD, Query.ofJson("/test_not_found/test.json"),
                1000, false);
        assertThat(future1.get()).isNull();

        // Legacy client doesn't support this feature.
        if (clientType == ClientType.LEGACY) {
            return;
        }

        final CompletableFuture<Entry<JsonNode>> future2 = client.watchFile(
                dogma.project(), dogma.repo1(), Revision.HEAD, Query.ofJson("/test_not_found/test.json"),
                1000, true);
        assertThatThrownBy(() -> future2.get()).getCause().isInstanceOf(EntryNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchJsonAsText(ClientType clientType) throws InterruptedException {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final Watcher<JsonNode> jsonWatcher = client.forRepo(dogma.project(), dogma.repo1())
                                                    .watch(Query.ofJson("/test/test2.json"))
                                                    .forever();
        assertThatJson(jsonWatcher.awaitInitialValue().value()).isEqualTo("{\"a\":\"apple\"}");
        jsonWatcher.close();

        final Watcher<String> stringWatcher = client.forRepo(dogma.project(), dogma.repo1())
                                                    .watch(Query.ofText("/test/test2.json"))
                                                    .forever();
        assertThat(stringWatcher.awaitInitialValue().value()).isEqualTo("{\"a\":\"apple\"}");
        stringWatcher.close();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watcherThrowsException(ClientType clientType) throws InterruptedException {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final String filePath = "/test/test2.json";
        final Watcher<JsonNode> jsonWatcher = client.forRepo(dogma.project(), dogma.repo1())
                                                    .watch(Query.ofJson(filePath))
                                                    .forever();

        // wait for initial value
        assertThatJson(jsonWatcher.awaitInitialValue().value()).isEqualTo("{\"a\":\"apple\"}");
        final Revision rev0 = jsonWatcher.initialValueFuture().join().revision();

        // add two watchers, the first one throws an exception
        final AtomicBoolean atomicBoolean = new AtomicBoolean();
        jsonWatcher.watch(node -> {
            throw new IllegalArgumentException();
        });
        jsonWatcher.watch(node -> {
            if ("air".equals(node.get("a").asText())) {
                atomicBoolean.set(true);
            }
        });

        // update the json
        final Change<JsonNode> update = Change.ofJsonUpsert(
                filePath, "{ \"a\": \"air\" }");
        client.forRepo(dogma.project(), dogma.repo1())
              .commit("Modify /a", update)
              .push(rev0)
              .join();

        // the updated json should be reflected in the second watcher
        await().untilTrue(atomicBoolean);
        jsonWatcher.close();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void transformingWatcher(ClientType clientType) throws InterruptedException {
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final String filePath = "/test/test2.json";
        final Watcher<JsonNode> heavyWatcher = client.forRepo(dogma.project(), dogma.repo1())
                                                     .watch(Query.ofJsonPath(filePath))
                                                     .forever();

        final Watcher<JsonNode> forExisting = Watcher.atJsonPointer(heavyWatcher, "/a");
        final AtomicReference<Latest<JsonNode>> watchResult = new AtomicReference<>();
        final AtomicInteger triggeredCount = new AtomicInteger();
        forExisting.watch((rev, node) -> {
            watchResult.set(new Latest<>(rev, node));
            triggeredCount.incrementAndGet();
        });

        // After the initial value is fetched, `latest` points to the specified JSON path
        final Latest<JsonNode> initialValue = forExisting.awaitInitialValue();
        await().untilAtomic(triggeredCount, Matchers.is(1));

        final Revision rev0 = client
                .normalizeRevision(dogma.project(), dogma.repo1(), Revision.HEAD)
                .join();
        assertThat(initialValue.revision()).isEqualTo(rev0);
        assertThat(initialValue.value()).isEqualTo(new TextNode("apple"));
        assertThat(forExisting.latest()).isEqualTo(initialValue);
        assertThat(watchResult.get()).isEqualTo(initialValue);

        // An irrelevant change should not trigger a notification.
        final Change<JsonNode> unrelatedChange = Change.ofJsonUpsert(
                filePath, "{ \"a\": \"apple\", \"b\": \"banana\" }");
        final Revision rev1 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Add /b", unrelatedChange)
                                    .push(rev0)
                                    .join()
                                    .revision();

        assertThat(triggeredCount.get()).isEqualTo(1);
        assertThat(watchResult.get()).isEqualTo(initialValue);

        // An relevant change should trigger a notification.
        final Change<JsonNode> relatedChange = Change.ofJsonUpsert(
                filePath, "{ \"a\": \"artichoke\", \"b\": \"banana\" }");
        final Revision rev2 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Change /a", relatedChange)
                                    .push(rev1)
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
        final Revision rev3 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Change /a again", nextRelatedChange)
                                    .push(rev2)
                                    .join()
                                    .revision();

        Thread.sleep(1100); // DELAY_ON_SUCCESS_MILLIS + epsilon
        assertThat(forExisting.latest()).isEqualTo(new Latest<>(rev2, new TextNode("artichoke")));
        assertThat(watchResult.get()).isEqualTo(forExisting.latest());
        assertThat(triggeredCount.get()).isEqualTo(2);
        assertThat(heavyWatcher.latestValue().at("/a")).isEqualTo(new TextNode("apricot"));
        assertThat(heavyWatcher.latest().revision()).isEqualTo(rev3);
        heavyWatcher.close();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void transformingThread_withDefault(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final String filePath = "/test/test.txt";
        final Watcher<String> watcher =
                client.forRepo(dogma.project(), dogma.repo1())
                      .watch(Query.ofText(filePath))
                      .forever()
                      .map(text -> {
                          assertThat(Thread.currentThread().getName())
                                  .startsWith(THREAD_NAME_PREFIX);
                          return text;
                      });

        final AtomicReference<String> threadName = new AtomicReference<>();
        watcher.watch(watched -> threadName.set(Thread.currentThread().getName()));
        client.forRepo(dogma.project(), dogma.repo1())
              .commit("test", Change.ofTextUpsert("/test/test.txt", "foo"))
              .push(Revision.HEAD);

        await().untilAtomic(threadName, Matchers.startsWith(THREAD_NAME_PREFIX));
        threadName.set(null);
        watcher.close();

        final Watcher<Revision> watcher2 =
                client.forRepo(dogma.project(), dogma.repo1())
                      .watch(PathPattern.of(filePath))
                      .forever()
                      .map(revision -> {
                          assertThat(Thread.currentThread().getName())
                                  .startsWith(THREAD_NAME_PREFIX);
                          return revision;
                      });
        watcher2.watch((revision1, revision2) -> threadName.set(Thread.currentThread().getName()));
        await().untilAtomic(threadName, Matchers.startsWith(THREAD_NAME_PREFIX));
        watcher2.close();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void transformingThread_withCustom(ClientType clientType) {
        final String threadNamePrefix = "custom-executor";
        final ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(
                        ThreadFactories.newThreadFactory(threadNamePrefix, true));
        final CentralDogma client = clientType.client(dogma);
        final String filePath = "/test/test.txt";
        final Watcher<String> watcher =
                client.forRepo(dogma.project(), dogma.repo1())
                      .watch(Query.ofText(filePath))
                      .forever()
                      .map(text -> {
                          assertThat(Thread.currentThread().getName())
                                  .startsWith(threadNamePrefix);
                          return text;
                      }, executor);

        final AtomicReference<String> threadName = new AtomicReference<>();
        watcher.watch(watched -> threadName.set(Thread.currentThread().getName()), executor);
        client.forRepo(dogma.project(), dogma.repo1())
              .commit("test", Change.ofTextUpsert("/test/test.txt", "foo"))
              .push(Revision.HEAD);

        await().untilAtomic(threadName, Matchers.startsWith(threadNamePrefix));
        threadName.set(null);
        watcher.close();

        final Watcher<Revision> watcher2 =
                client.forRepo(dogma.project(), dogma.repo1())
                      .watch(PathPattern.of(filePath))
                      .forever()
                      .map(revision -> {
                          assertThat(Thread.currentThread().getName())
                                  .startsWith(threadNamePrefix);
                          return revision;
                      }, executor);
        watcher2.watch((revision1, revision2) -> threadName.set(Thread.currentThread().getName()), executor);
        await().untilAtomic(threadName, Matchers.startsWith(threadNamePrefix));
        watcher2.close();
    }

    @ParameterizedTest
    @EnumSource(value = ClientType.class, mode = Mode.EXCLUDE, names = "LEGACY")
    void fileWatcher_errorOnEntryNotFound(ClientType clientType) {
        // prepare test
        revertTestFiles(clientType);
        final CentralDogma client = clientType.client(dogma);
        final String filePath = "/test_not_found/test.json";

        // create watcher
        final Watcher<JsonNode> watcher = client.forRepo(dogma.project(), dogma.repo1())
                                                .watch(Query.ofJson(filePath))
                                                .errorOnEntryNotFound(true)
                                                .timeoutMillis(100).forever();

        // check entry does not exist when to get initial value
        assertThatThrownBy(watcher::awaitInitialValue)
                .getRootCause().isInstanceOf(EntryNotFoundException.class);
        assertThatThrownBy(() -> watcher.awaitInitialValue(100, TimeUnit.MILLISECONDS))
                .getRootCause().isInstanceOf(EntryNotFoundException.class);
        assertThatThrownBy(() -> watcher.awaitInitialValue(100, TimeUnit.MILLISECONDS, new TextNode("test")))
                .getRootCause().isInstanceOf(EntryNotFoundException.class);

        // when initialValueFuture throw 'EntryNotFoundException', you can't use 'watch' method.
        assertThatThrownBy(() -> watcher.watch((rev, node) -> {
        })).isInstanceOf(IllegalStateException.class);
        watcher.close();
    }

    @ParameterizedTest
    @EnumSource(value = ClientType.class, mode = Mode.EXCLUDE, names = "LEGACY")
    void fileWatcher_errorOnEntryNotFound_watchIsNotWorking(ClientType clientType) throws Exception {
        // prepare test
        revertTestFiles(clientType);
        final CentralDogma client = clientType.client(dogma);
        final String filePath = "/test_not_found/test.json";

        // create watcher
        final Watcher<JsonNode> watcher = client.forRepo(dogma.project(), dogma.repo1())
                                                .watch(Query.ofJson(filePath))
                                                .timeoutMillis(100)
                                                .errorOnEntryNotFound(true)
                                                .forever();

        final AtomicReference<Latest<JsonNode>> watchResult = new AtomicReference<>();
        final AtomicInteger triggeredCount = new AtomicInteger();
        watcher.watch((rev, node) -> {
            watchResult.set(new Latest<>(rev, node));
            triggeredCount.incrementAndGet();
        });

        // check entry does not exist when to get initial value
        assertThatThrownBy(watcher::awaitInitialValue).getRootCause().isInstanceOf(
                EntryNotFoundException.class);

        // add file
        final Change<JsonNode> change1 = Change.ofJsonUpsert(
                filePath, "{ \"a\": \"apple\", \"b\": \"banana\" }");
        client.forRepo(dogma.project(), dogma.repo1())
              .commit("Add /a /b", change1)
              .push(Revision.HEAD)
              .join();

        // Wait over the timeoutMillis(100) + a
        Thread.sleep(1000);
        // check watch is not working
        assertThat(triggeredCount.get()).isEqualTo(0);
        assertThat(watchResult.get()).isEqualTo(null);
        watcher.close();
    }

    @ParameterizedTest
    @EnumSource(value = ClientType.class, mode = Mode.EXCLUDE, names = "LEGACY")
    void fileWatcher_errorOnEntryNotFound_EntryIsRemovedOnWatching(ClientType clientType) throws Exception {
        // prepare test
        revertTestFiles(clientType);
        final CentralDogma client = clientType.client(dogma);
        final String filePath = "/test/test2.json";

        // create watcher
        final Watcher<JsonNode> watcher = client.forRepo(dogma.project(), dogma.repo1())
                                                .watch(Query.ofJson(filePath))
                                                .timeoutMillis(100)
                                                .errorOnEntryNotFound(true)
                                                .forever();

        final AtomicReference<Latest<JsonNode>> watchResult = new AtomicReference<>();
        final AtomicInteger triggeredCount = new AtomicInteger();
        watcher.initialValueFuture().thenAccept(result -> watcher.watch((rev, node) -> {
            watchResult.set(new Latest<>(rev, node));
            triggeredCount.incrementAndGet();
        }));

        // check initial value
        assertThatJson(watcher.awaitInitialValue().value()).isEqualTo("{\"a\":\"apple\"}");
        await().untilAtomic(triggeredCount, Matchers.is(1));

        final Revision rev0 = watcher.initialValueFuture().join().revision();

        // change file
        final Change<JsonNode> change1 = Change.ofJsonUpsert(
                filePath, "{ \"a\": \"artichoke\"}");
        final Revision rev1 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Change /a", change1)
                                    .push(rev0)
                                    .join()
                                    .revision();
        await().untilAtomic(triggeredCount, Matchers.is(2));
        assertThat(watchResult.get()).isEqualTo(watcher.latest());

        // remove file
        final Change<Void> change2 = Change.ofRemoval(filePath);
        final Revision rev2 = client.forRepo(dogma.project(), dogma.repo1())
                                    .commit("Removal", change2).push(rev1)
                                    .join()
                                    .revision();

        // Wait over the timeoutMillis(100) + a
        Thread.sleep(1000);

        // check utilize latest data before removal
        assertThat(triggeredCount.get()).isEqualTo(2);
        assertThat(watchResult.get()).isEqualTo(watcher.latest());

        // add file
        final Change<JsonNode> change3 = Change.ofJsonUpsert(
                filePath, "{ \"a\": \"apricot\", \"b\": \"banana\" }");
        client.forRepo(dogma.project(), dogma.repo1())
              .commit("Add /a /b", change3)
              .push(rev2)
              .join();
        await().untilAtomic(triggeredCount, Matchers.is(3));
        assertThat(watchResult.get()).isEqualTo(watcher.latest());
        watcher.close();
    }

    @ParameterizedTest
    @EnumSource(value = ClientType.class, mode = Mode.EXCLUDE, names = "LEGACY")
    void repositoryWatcher_errorOnEntryNotFound(ClientType clientType) {
        // prepare test
        revertTestFiles(clientType);
        final CentralDogma client = clientType.client(dogma);
        final String pathPattern = "/test_not_found/**";

        // create watcher
        final Watcher<Revision> watcher = client.forRepo(dogma.project(), dogma.repo1())
                                                .watch(PathPattern.of(pathPattern))
                                                .timeoutMillis(100)
                                                .errorOnEntryNotFound(true)
                                                .forever();

        // check entry does not exist when to get initial value
        assertThatThrownBy(watcher::awaitInitialValue)
                .getRootCause().isInstanceOf(EntryNotFoundException.class);
        assertThatThrownBy(() -> watcher.awaitInitialValue(100, TimeUnit.MILLISECONDS))
                .getRootCause().isInstanceOf(EntryNotFoundException.class);
        assertThatThrownBy(() -> watcher.awaitInitialValue(100, TimeUnit.MILLISECONDS, Revision.INIT))
                .getRootCause().isInstanceOf(EntryNotFoundException.class);

        // when initialValueFuture throw 'EntryNotFoundException', you can't use 'watch' method.
        await().untilAsserted(() -> assertThatThrownBy(
                () -> watcher.watch((rev, node) -> {
                }))
                .isInstanceOf(IllegalStateException.class));
    }

    @ParameterizedTest
    @EnumSource(value = ClientType.class, mode = Mode.EXCLUDE, names = "LEGACY")
    void repositoryWatcher_errorOnEntryNotFound_watchIsNotWorking(ClientType clientType) throws Exception {
        // prepare test
        revertTestFiles(clientType);

        final CentralDogma client = clientType.client(dogma);
        final String pathPattern = "/test_not_found/**";
        final String filePath = "/test_not_found/test.json";

        final Watcher<Revision> watcher = client.forRepo(dogma.project(), dogma.repo1())
                                                .watch(PathPattern.of(pathPattern))
                                                .timeoutMillis(100)
                                                .errorOnEntryNotFound(true)
                                                .forever();

        final AtomicReference<Revision> watchResult = new AtomicReference<>();
        final AtomicInteger triggeredCount = new AtomicInteger();
        watcher.watch(rev -> {
            watchResult.set(rev);
            triggeredCount.incrementAndGet();
        });

        // check entry does not exist when to get initial value
        assertThatThrownBy(watcher::awaitInitialValue).getRootCause().isInstanceOf(
                EntryNotFoundException.class);

        // add file
        final Change<JsonNode> change1 = Change.ofJsonUpsert(
                filePath, "{ \"a\": \"apple\", \"b\": \"banana\" }");
        client.forRepo(dogma.project(), dogma.repo1())
              .commit("Add /a /b", change1)
              .push(Revision.HEAD)
              .join();

        // Wait over the timeoutMillis(100) + a
        Thread.sleep(1000);
        // check watch is not working
        assertThat(triggeredCount.get()).isEqualTo(0);
        assertThat(watchResult.get()).isEqualTo(null);
    }

    private static void revertTestFiles(ClientType clientType) {
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/test/test1.json", "[ 1, 2, 3 ]");
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/test/test2.json", "{ \"a\": \"apple\" }");

        final List<Change<JsonNode>> changes = Arrays.asList(change1, change2);
        final CentralDogma client = clientType.client(dogma);

        if (!client.getPreviewDiffs(dogma.project(), dogma.repo1(), Revision.HEAD, changes)
                   .join().isEmpty()) {
            client.forRepo(dogma.project(), dogma.repo1())
                  .commit("Revert test files", changes)
                  .push(Revision.HEAD)
                  .join();
        }

        final Change<Void> change3 = Change.ofRemoval("/test_not_found/test.json");
        final Map<String, EntryType> files = client.listFiles(dogma.project(), dogma.repo1(), Revision.HEAD,
                                                              PathPattern.of("/test_not_found/**")).join();
        if (files.containsKey(change3.path())) {
            client.forRepo(dogma.project(), dogma.repo1())
                  .commit("Remove test files", change3)
                  .push(Revision.HEAD)
                  .join();
        }
    }
}
