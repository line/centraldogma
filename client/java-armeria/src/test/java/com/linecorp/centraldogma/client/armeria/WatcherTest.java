/*
 * Copyright 2021 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class WatcherTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar")
                  .join()
                  .commit("Add baz.txt", Change.ofTextUpsert("/baz.txt", ""))
                  .push().join();
        }
    };

    @Test
    void mapperIsCalledOnlyOnceWhenFileIsChanged() throws Exception {
        final Watcher<String> watcher = dogma.client()
                                             .forRepo("foo", "bar")
                                             .watcher(Query.ofText("/baz.txt"))
                                             .start();
        assertThat(watcher.initialValueFuture().join().value()).isEmpty();
        final AtomicInteger mapperCounter = new AtomicInteger();
        final Watcher<Integer> childWatcher = watcher.newChild(str -> mapperCounter.getAndIncrement());
        for (int i = 0; i < 10; i++) {
            childWatcher.watch(value -> {
                /* no-op */
            });
        }

        Thread.sleep(1000);
        // mapperCount is called for the first latest.
        assertThat(mapperCounter.get()).isOne();

        dogma.client()
             .forRepo("foo", "bar")
             .commit("Modify baz.txt", Change.ofTextUpsert("/baz.txt", "1"))
             .push()
             .join();

        // mapperCount is called only once when the value is updated.
        await().until(() -> mapperCounter.get() == 2);
        Thread.sleep(1000);
        // It's still the same.
        assertThat(mapperCounter.get()).isEqualTo(2);
        watcher.close();
    }

    @Test
    void multipleMap() throws Exception {
        final Watcher<Boolean> watcher = dogma.client()
                                              .forRepo("foo", "bar")
                                              .watcher(Query.ofText("/baz.txt"))
                                              .map(txt -> 1)
                                              .map(intValue -> Integer.toString(intValue))
                                              .map("1"::equals)
                                              .start();

        assertThat(watcher.initialValueFuture().join().value()).isTrue();
        watcher.close();
    }

    @Test
    void multipleNewChild() throws Exception {
        final Watcher<String> originalWatcher = dogma.client()
                                                     .forRepo("foo", "bar")
                                                     .watcher(Query.ofText("/baz.txt"))
                                                     .start();
        final Watcher<Boolean> watcher = originalWatcher
                .newChild(txt -> 1)
                .newChild(intValue -> Integer.toString(intValue))
                .newChild("1"::equals);

        assertThat(watcher.initialValueFuture().join().value()).isTrue();
        originalWatcher.close();
    }

    @Test
    void mapperException() {
        final Watcher<String> originalWatcher = dogma.client()
                                                     .forRepo("foo", "bar")
                                                     .watcher(Query.ofText("/baz.txt"))
                                                     .start();
        originalWatcher.initialValueFuture().join();
        final Watcher<String> watcher = originalWatcher.newChild(unused -> {
            throw new RuntimeException();
        }).newChild(val -> "not called");
        await().untilAsserted(() -> assertThatThrownBy(() -> watcher.initialValueFuture().join())
                .hasCauseExactlyInstanceOf(RuntimeException.class));
        originalWatcher.close();
    }
}
