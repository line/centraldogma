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

package com.linecorp.centraldogma.server.command;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.internal.jackson.Jackson;
import com.linecorp.centraldogma.server.auth.Session;

class CreateSessionCommandTest {

    @Test
    void testJsonConversion() throws Exception {
        final Session session =
                new Session("session-id-12345",
                            "foo",
                            Instant.EPOCH,
                            Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
                            "abc");

        // Convert the object with Jackson because a serializer and deserializer for Instant type are
        // added to Jackson.
        final JsonNode node = Jackson.ofJson().valueToTree(
                new CreateSessionCommand(1234L,
                                         new Author("foo", "bar@baz.com"),
                                         session));

        final String expectedRawSession = toSerializedBase64("abc");
        assertThatJson(node)
                .withTolerance(0.000000001)
                .isEqualTo(
                        '{' +
                        "  \"type\": \"CREATE_SESSIONS\"," +
                        "  \"timestamp\": 1234," +
                        "  \"author\": {" +
                        "    \"name\": \"foo\"," +
                        "    \"email\": \"bar@baz.com\"" +
                        "  }," +
                        "  \"session\": {\n" +
                        "    \"id\": \"session-id-12345\"," +
                        "    \"username\": \"foo\"," +
                        "    \"creationTime\": 0," +
                        "    \"expirationTime\": 60," +
                        "    \"rawSession\": \"" + expectedRawSession + '"' +
                        "  }" +
                        '}');
    }

    private static String toSerializedBase64(Object object) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(object);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }
}
