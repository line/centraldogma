/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.centraldogma.internal;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class YamlTest {

    // Basic YAML Parsing

    @Test
    void simpleKeyValue() throws Exception {
        //language=yaml
        final String yaml = "key: value";
        final JsonNode node = Yaml.readTree(yaml);
        assertThatJson(node).node("key").isEqualTo("value");
    }

    @Test
    void multipleKeyValues() throws Exception {
        //language=yaml
        final String yaml = "name: John\n" +
                            "age: 30\n" +
                            "active: true";
        final JsonNode node = Yaml.readTree(yaml);
        assertThatJson(node).node("name").isEqualTo("John");
        assertThatJson(node).node("age").isEqualTo(30);
        assertThatJson(node).node("active").isEqualTo(true);
    }

    @Test
    void nestedObjects() throws Exception {
        //language=yaml
        final String yaml = "person:\n" +
                            "  name: Jane\n" +
                            "  address:\n" +
                            "    city: Seoul\n" +
                            "    country: Korea";
        final JsonNode node = Yaml.readTree(yaml);
        assertThatJson(node).node("person.name").isEqualTo("Jane");
        assertThatJson(node).node("person.address.city").isEqualTo("Seoul");
        assertThatJson(node).node("person.address.country").isEqualTo("Korea");
    }

    @Test
    void arrays() throws Exception {
        //language=yaml
        final String yaml = "fruits:\n" +
                            "  - apple\n" +
                            "  - banana\n" +
                            "  - cherry";
        final JsonNode node = Yaml.readTree(yaml);
        assertThatJson(node).node("fruits").isArray().ofLength(3);
        assertThatJson(node).node("fruits[0]").isEqualTo("apple");
        assertThatJson(node).node("fruits[1]").isEqualTo("banana");
        assertThatJson(node).node("fruits[2]").isEqualTo("cherry");
    }
}
