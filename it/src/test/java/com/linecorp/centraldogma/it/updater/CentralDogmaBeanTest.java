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

package com.linecorp.centraldogma.it.updater;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.updater.CentralDogmaBean;
import com.linecorp.centraldogma.client.updater.CentralDogmaBeanConfigBuilder;
import com.linecorp.centraldogma.client.updater.CentralDogmaBeanFactory;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class CentralDogmaBeanTest {

    @ClassRule
    public static final CentralDogmaRule dogma = new CentralDogmaRule() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("a").join();
            client.createRepository("a", "b").join();
            client.createProject("alice").join();
            client.createRepository("alice", "bob").join();
        }
    };

    private final ObjectMapper objectMapper = new ObjectMapper().disable(FAIL_ON_UNKNOWN_PROPERTIES);

    private CentralDogmaBeanFactory factory;

    @Before
    public void setup() {
        factory = new CentralDogmaBeanFactory(dogma.client(), objectMapper);
    }

    @Test
    public void test() throws Exception {
        final CentralDogma client = dogma.client();
        final TestProperty property = factory.get(new TestProperty(), TestProperty.class);

        assertThat(property.getFoo()).isEqualTo(10);
        assertThat(property.getBar()).isEqualTo("20");
        assertThat(property.getQux()).containsExactly("x", "y", "z");

        client.push("a", "b", Revision.HEAD, Author.SYSTEM, "Add a.json",
                    Change.ofJsonUpsert("/c.json",
                                        '{' +
                                        "  \"foo\": 20," +
                                        "  \"bar\": \"Y\"," +
                                        "  \"qux\": [\"0\", \"1\"]" +
                                        '}'))
              .join();

        client.watchFile("a", "b", Revision.INIT, Query.identity("/a.json"), 5000).join();

        // Wait until the changes are handled.
        await().atMost(5, TimeUnit.SECONDS).until(() -> property.getFoo() == 20);

        assertThat(property.getBar()).isEqualTo("Y");
        assertThat(property.getQux()).containsExactly("0", "1");
    }

    @Test
    public void overrideSettings() throws Exception {
        final CentralDogma client = dogma.client();

        client.push("alice", "bob", Revision.HEAD, Author.SYSTEM, "Add charlie.json",
                    Change.ofJsonUpsert("/charlie.json",
                                        "[{" +
                                        "  \"foo\": 200," +
                                        "  \"bar\": \"YY\"," +
                                        "  \"qux\": [\"100\", \"200\"]" +
                                        "}]"))
              .join();

        TestProperty property = factory.get(new TestProperty(), TestProperty.class,
                                            new CentralDogmaBeanConfigBuilder()
                                                    .project("alice")
                                                    .repository("bob")
                                                    .path("/charlie.json")
                                                    .jsonPath("$[0]")
                                                    .build());

        await().atMost(5, TimeUnit.SECONDS).until(() -> property.getFoo() == 200);
        assertThat(property.getBar()).isEqualTo("YY");
        assertThat(property.getQux()).containsExactly("100", "200");
    }

    @CentralDogmaBean(project = "a", repository = "b", path = "/c.json")
    static class TestProperty {
        int foo = 10;
        String bar = "20";
        List<String> qux = ImmutableList.of("x", "y", "z");

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
