/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.centraldogma.common;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;

public class EntryTest {

    @Test
    public void ofDirectory() throws Exception {
        final Entry<Void> e = Entry.ofDirectory(new Revision(1), "/");
        assertThat(e.revision()).isEqualTo(new Revision(1));
        assertThat(e.hasContent()).isFalse();
        e.ifHasContent(unused -> fail());
        assertThatThrownBy(e::content).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(e::contentAsJson).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(e::contentAsText).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(e::contentAsPrettyText).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> e.contentAsJson(JsonNode.class)).isInstanceOf(IllegalStateException.class);

        // directory vs. directory
        final Entry<Void> e2 = Entry.ofDirectory(new Revision(1), "/");
        assertThat(e).isEqualTo(e2);
        assertThat(e.hashCode()).isEqualTo(e2.hashCode());
        assertThat(e).isNotEqualTo(Entry.ofDirectory(new Revision(2), "/"));
        assertThat(e).isNotEqualTo(Entry.ofDirectory(new Revision(1), "/foo"));

        // directory vs. text file
        final Entry<String> e3 = Entry.ofText(new Revision(1), "/a.txt", "foo");
        assertThat(e).isNotEqualTo(e3);
        assertThat(e.hashCode()).isNotEqualTo(e3.hashCode());

        // directory vs. JSON file
        final Entry<JsonNode> e4 = Entry.ofJson(new Revision(1), "/a.json", "{ \"foo\": \"bar\" }");
        assertThat(e).isNotEqualTo(e4);
        assertThat(e.hashCode()).isNotEqualTo(e4.hashCode());
    }

    @Test
    public void ofText() throws Exception {
        final Entry<String> e = Entry.ofText(new Revision(1), "/a.txt", "foo");
        assertThat(e.revision()).isEqualTo(new Revision(1));
        assertThat(e.hasContent()).isTrue();
        e.ifHasContent(content -> assertThat(content).isEqualTo("foo"));
        assertThat(e.content()).isEqualTo("foo");
        assertThat(e.contentAsText()).isEqualTo("foo");
        assertThat(e.contentAsPrettyText()).isEqualTo("foo");
        assertThatThrownBy(e::contentAsJson).isInstanceOf(JsonParseException.class);
        assertThatThrownBy(() -> e.contentAsJson(JsonNode.class)).isInstanceOf(JsonParseException.class);

        // contentAsJson() should work if the text content is JSON.
        assertThat(Entry.ofText(new Revision(1), "/a.txt", "null").contentAsJson())
                .isEqualTo(Jackson.nullNode);
        assertThat(Entry.ofText(new Revision(1), "/a.txt", "null").contentAsJson(JsonNode.class))
                .isEqualTo(Jackson.nullNode);

        // text file vs. text file
        final Entry<String> e2 = Entry.ofText(new Revision(1), "/a.txt", "foo");
        assertThat(e).isEqualTo(e2);
        assertThat(e.hashCode()).isEqualTo(e2.hashCode());
        assertThat(e).isNotEqualTo(Entry.ofText(new Revision(2), "/a.txt", "foo"));
        assertThat(e).isNotEqualTo(Entry.ofText(new Revision(1), "/b.txt", "foo"));
        assertThat(e).isNotEqualTo(Entry.ofText(new Revision(1), "/a.txt", "bar"));

        // text file vs. JSON file
        final Entry<JsonNode> e3 = Entry.ofJson(new Revision(1), "/a.json", "{ \"foo\": \"bar\" }");
        assertThat(e).isNotEqualTo(e3);
        assertThat(e.hashCode()).isNotEqualTo(e3.hashCode());

        // text file vs. directory
        final Entry<Void> e4 = Entry.ofDirectory(new Revision(1), "/foo");
        assertThat(e).isNotEqualTo(e4);
        assertThat(e.hashCode()).isNotEqualTo(e4.hashCode());
    }

    @Test
    public void ofJson() throws Exception {
        final Entry<JsonNode> e = Entry.ofJson(new Revision(1), "/a.json", "{ \"foo\": \"bar\" }");
        assertThat(e.revision()).isEqualTo(new Revision(1));
        assertThat(e.hasContent()).isTrue();
        e.ifHasContent(content -> assertThatJson(content).isEqualTo("{ \"foo\": \"bar\" }"));
        assertThatJson(e.content()).isEqualTo("{ \"foo\": \"bar\" }");
        assertThat(e.contentAsText()).isEqualTo("{\"foo\":\"bar\"}");
        assertThat(e.contentAsPrettyText()).isEqualTo("{\n  \"foo\": \"bar\"\n}");
        assertThat(e.content()).isSameAs(e.contentAsJson());
        assertThat(e.content()).isEqualTo(e.contentAsJson(JsonNode.class));

        // JSON file vs. JSON file
        final Entry<JsonNode> e2 = Entry.ofJson(new Revision(1), "/a.json", "{ \"foo\": \"bar\" }");
        assertThat(e).isEqualTo(e2);
        assertThat(e.hashCode()).isEqualTo(e2.hashCode());
        assertThat(e).isNotEqualTo(Entry.ofJson(new Revision(2), "/a.json", "{ \"foo\": \"bar\" }"));
        assertThat(e).isNotEqualTo(Entry.ofJson(new Revision(1), "/b.json", "{ \"foo\": \"bar\" }"));
        assertThat(e).isNotEqualTo(Entry.ofJson(new Revision(1), "/a.json", "null"));

        // JSON file vs. text file
        final Entry<String> e3 = Entry.ofText(new Revision(1), "/a.json", "{\"foo\":\"bar\"}");
        assertThat(e).isNotEqualTo(e3);
        assertThat(e.hashCode()).isNotEqualTo(e3.hashCode());

        // JSON file vs. directory
        final Entry<Void> e4 = Entry.ofDirectory(new Revision(1), "/foo");
        assertThat(e).isNotEqualTo(e4);
        assertThat(e.hashCode()).isNotEqualTo(e4.hashCode());
    }

    @Test
    public void of() {
        // Null checks
        assertThatThrownBy(() -> Entry.of(null, "/1.txt", EntryType.TEXT, "1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Entry.of(new Revision(1), null, EntryType.TEXT, "1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Entry.of(new Revision(1), "/1.txt", null, "1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Entry.of(new Revision(1), "/1.txt", EntryType.TEXT, null))
                .isInstanceOf(NullPointerException.class);

        // Type check
        assertThatThrownBy(() -> Entry.of(new Revision(1), "/1.txt", EntryType.TEXT, new Object()))
                .isInstanceOf(ClassCastException.class);

        // Directory
        Entry.of(new Revision(1), "/a", EntryType.DIRECTORY, null);
        assertThatThrownBy(() -> Entry.of(new Revision(1), "/a", EntryType.DIRECTORY, "foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected: null");
    }

    @Test
    public void testEquals() {
        final Entry<Void> e = Entry.ofDirectory(new Revision(1), "/foo");
        assertThat(e).isNotEqualTo(null);
        assertThat(e).isNotEqualTo(new Object());
        assertThat(e).isEqualTo(e);
    }

    @Test
    public void testToString() {
        assertThat(Entry.ofText(new Revision(1), "/a.txt", "a").toString()).isNotEmpty();
    }
}
