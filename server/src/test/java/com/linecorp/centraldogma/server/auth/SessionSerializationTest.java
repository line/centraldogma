/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.auth;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;

class SessionSerializationTest {

    @Test
    void serialize() {
        Session session = new Session("session-id-12345",
                                      null,
                                      "foo",
                                      Instant.EPOCH,
                                      Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
                                      "ignored raw session");
        JsonNode node = Jackson.valueToTree(session);
        assertThatJson(node)
                .withTolerance(0.000000001)
                .isEqualTo('{' +
                           "  \"id\": \"session-id-12345\"," +
                           "  \"username\": \"foo\"," +
                           "  \"creationTime\": 0," +
                           "  \"expirationTime\": 60" +
                           '}');

        session = new Session("session-id-12345",
                              "csrfToken",
                              "foo",
                              Instant.EPOCH,
                              Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
                              "ignored raw session");
        node = Jackson.valueToTree(session);
        assertThatJson(node)
                .withTolerance(0.000000001)
                .isEqualTo('{' +
                           "  \"id\": \"session-id-12345\"," +
                           "  \"csrfToken\": \"csrfToken\"," +
                           "  \"username\": \"foo\"," +
                           "  \"creationTime\": 0," +
                           "  \"expirationTime\": 60" +
                           '}');
    }

    @Test
    void deserialize() throws Exception {
        String sessionString = '{' +
                               "  \"id\": \"session-id-12345\"," +
                               "  \"username\": \"foo\"," +
                               "  \"creationTime\": 0," +
                               "  \"expirationTime\": 60," +
                               "  \"rawSession\": \"" + toSerializedBase64("ignored") + '"' +
                               '}';
        Session session = Jackson.readValue(sessionString, Session.class);
        assertThat(session.id()).isEqualTo("session-id-12345");
        assertThat(session.csrfToken()).isNull();
        assertThat(session.username()).isEqualTo("foo");

        sessionString = '{' +
                        "  \"id\": \"session-id-12345\"," +
                        "  \"csrfToken\": \"csrfToken\"," +
                        "  \"username\": \"foo\"," +
                        "  \"creationTime\": 0," +
                        "  \"expirationTime\": 60," +
                        "  \"rawSession\": \"" + toSerializedBase64("ignored") + '"' +
                        '}';
        session = Jackson.readValue(sessionString, Session.class);
        assertThat(session.id()).isEqualTo("session-id-12345");
        assertThat(session.csrfToken()).isEqualTo("csrfToken");
        assertThat(session.username()).isEqualTo("foo");
    }

    private static String toSerializedBase64(String str) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(str);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }
}
