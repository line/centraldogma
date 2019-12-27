/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.centraldogma.it.updater;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.ThrowableAssert.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.updater.CentralDogmaBean;
import com.linecorp.centraldogma.client.updater.CentralDogmaBeanConfigBuilder;
import com.linecorp.centraldogma.client.updater.CentralDogmaBeanFactory;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class CentralDogmaBeanTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("a").join();
            client.createRepository("a", "b").join();
            client.createProject("alice").join();
            client.createRepository("alice", "bob").join();
            client.createProject("e").join();
            client.createRepository("e", "f").join();
        }
    };

    private final ObjectMapper objectMapper = new ObjectMapper().disable(FAIL_ON_UNKNOWN_PROPERTIES);

    private CentralDogmaBeanFactory factory;

    @BeforeEach
    void setUp() {
        factory = new CentralDogmaBeanFactory(dogma.client(), objectMapper);
    }

    @Test
    void stayDefault() {
        final TestPropertyDefault property = factory.get(new TestPropertyDefault(), TestPropertyDefault.class);

        // Delay to detect if data for this bean has already been written to the server.
        // Protects against newly added tests, when run in suite.
        await().until(() -> property.getFoo() == 10 &&
                            "20".equals(property.getBar()) &&
                            property.getQux().equals(ImmutableList.of("x", "y", "z")) &&
                            property.getRevision() == null);
    }

    @Test
    void test() {
        final int[] called = new int[1];
        final Consumer<TestProperty> listener = testProperty -> called[0] = 1;
        final CentralDogma client = dogma.client();
        final TestProperty property = factory.get(new TestProperty(), TestProperty.class, listener);

        final PushResult res = client.push("a", "b", Revision.HEAD, "Add c.json",
                                           Change.ofJsonUpsert("/c.json",
                                                               '{' +
                                                               "  \"foo\": 20," +
                                                               "  \"bar\": \"Y\"," +
                                                               "  \"qux\": [\"0\", \"1\"]" +
                                                               '}')).join();

        // Wait until the changes are handled.
        await().atMost(5000, TimeUnit.SECONDS).until(() -> property.getFoo() == 20);

        assertThat(property.getBar()).isEqualTo("Y");
        assertThat(property.getQux()).containsExactly("0", "1");
        assertThat(property.getRevision()).isNotNull();
        assertThat(called[0]).isEqualTo(1);

        // test that after close a watcher, it could not receive change anymore
        property.closeWatcher();
        client.push("a", "b", Revision.HEAD, "Modify c.json",
                    Change.ofJsonUpsert("/c.json",
                                        '{' +
                                        "  \"foo\": 50," +
                                        "  \"bar\": \"Y2\"," +
                                        "  \"qux\": [\"M\", \"N\"]" +
                                        '}'))
              .join();
        // TODO(huydx): this test may be flaky, is there any better way?
        final Throwable thrown = catchThrowable(() -> await().atMost(2, TimeUnit.SECONDS)
                                                             .until(() -> property.getFoo() == 50));
        assertThat(thrown).isInstanceOf(ConditionTimeoutException.class);

        // test that fail consumer will prevent it from receive change
        // TODO(huydx): this test may be flaky, is there any better way?
        final Consumer<TestProperty> failListener = testProperty -> {
            throw new RuntimeException("test runtime exception");
        };
        final TestProperty failProp = factory.get(new TestProperty(), TestProperty.class, failListener);
        client.push("a", "b", Revision.HEAD, "Add a.json",
                    Change.ofJsonUpsert("/c.json",
                                        '{' +
                                        "  \"foo\": 211," +
                                        "  \"bar\": \"Y\"," +
                                        "  \"qux\": [\"11\", \"1\"]" +
                                        '}'))
              .join();
        // await will fail due to exception is thrown before node get serialized
        // and revision will remain null
        final Throwable thrown2 = catchThrowable(() -> await().atMost(2, TimeUnit.SECONDS)
                                                              .until(() -> failProp.getFoo() == 211));
        assertThat(thrown2).isInstanceOf(ConditionTimeoutException.class);
        assertThat(failProp.getRevision()).isNull();
    }

    @Test
    void overrideSettings() {
        final CentralDogma client = dogma.client();

        client.push("alice", "bob", Revision.HEAD, "Add charlie.json",
                    Change.ofJsonUpsert("/charlie.json",
                                        "[{" +
                                        "  \"foo\": 200," +
                                        "  \"bar\": \"YY\"," +
                                        "  \"qux\": [\"100\", \"200\"]" +
                                        "}]"))
              .join();

        final TestProperty property = factory.get(new TestProperty(), TestProperty.class,
                                                  (TestProperty x) -> {},
                                                  new CentralDogmaBeanConfigBuilder()
                                                          .project("alice")
                                                          .repository("bob")
                                                          .path("/charlie.json")
                                                          .jsonPath("$[0]")
                                                          .build());

        await().atMost(5, TimeUnit.SECONDS).until(() -> property.getFoo() == 200);
        assertThat(property.getBar()).isEqualTo("YY");
        assertThat(property.getQux()).containsExactly("100", "200");
        assertThat(property.getRevision()).isEqualTo(new Revision("2"));

        // properly close watcher
        property.closeWatcher();
    }

    @Test
    void updateListenerIgnoreDefault() {
        final CentralDogma client = dogma.client();
        final AtomicReference<TestProperty> update = new AtomicReference<>();

        client.push("a", "b", Revision.HEAD, "Add c.json",
                    Change.ofJsonUpsert("/c.json",
                                        '{' +
                                        "  \"foo\": 21," +
                                        "  \"bar\": \"Y\"," +
                                        "  \"qux\": [\"0\", \"1\"]" +
                                        '}')).join();

        final TestProperty property = factory.get(new TestProperty(), TestProperty.class, update::set);
        await().atMost(5, TimeUnit.SECONDS).until(() -> update.get() != null);

        assertThat(property.getFoo()).isEqualTo(21);
        assertThat(property.getBar()).isEqualTo("Y");
        assertThat(property.getQux()).containsExactly("0", "1");
        assertThat(property.getRevision()).isNotNull();
    }

    @CentralDogmaBean(project = "a", repository = "b", path = "/c.json")
    static class TestProperty {
        int foo = 10;
        String bar = "20";
        List<String> qux = ImmutableList.of("x", "y", "z");

        @Nullable
        public Revision getRevision() {
            return null;
        }

        public int getFoo() {
            return foo;
        }

        public String getBar() {
            return bar;
        }

        public List<String> getQux() {
            return qux;
        }

        public void closeWatcher() { }
    }

    @CentralDogmaBean(project = "e", repository = "f", path = "/g.json")
    static class TestPropertyDefault {
        int foo = 10;
        String bar = "20";
        List<String> qux = ImmutableList.of("x", "y", "z");

        @Nullable
        public Revision getRevision() {
            return null;
        }

        public int getFoo() {
            return foo;
        }

        public String getBar() {
            return bar;
        }

        public List<String> getQux() {
            return qux;
        }
    }
}
