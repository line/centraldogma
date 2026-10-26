/*
 * Copyright 2026 LY Corporation
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
package com.linecorp.centraldogma.internal.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;

class CommitDtoTest {

    private static final String LEGACY_COMMIT_ID = "1111111111111111111111111111111111111111";
    private static final String UPSTREAM_COMMIT_ID = "2222222222222222222222222222222222222222";

    @Test
    void deserializesCommit() throws Exception {
        final CommitDto commit = Jackson.readValue(commitJson(true, true), CommitDto.class);

        assertThat(commit.revision().major()).isEqualTo(42);
        assertThat(commit.author().name()).isEqualTo("Alice");
        assertThat(commit.author().email()).isEqualTo("alice@example.com");
        assertThat(commit.commitMessage().summary()).isEqualTo("summary");
        assertThat(commit.commitMessage().detail()).isEqualTo("detail");
        assertThat(commit.commitMessage().markup()).isEqualTo(Markup.MARKDOWN);
        assertThat(commit.pushedAt()).isEqualTo("2026-09-16T00:00:00Z");
        assertThat(commit.upstreamCommitId()).isEqualTo(UPSTREAM_COMMIT_ID);
    }

    @Test
    void deserializesCommitWithoutUpstreamCommitId() throws Exception {
        final CommitDto commit = Jackson.readValue(commitJson(false, false), CommitDto.class);

        assertThat(commit.upstreamCommitId()).isNull();
    }

    @Test
    void ignoresLegacyCommitId() throws Exception {
        final CommitDto commit = Jackson.readValue(commitJson(true, false), CommitDto.class);

        assertThat(commit.upstreamCommitId()).isNull();
    }

    @Test
    void doesNotSerializeInternalCommitId() {
        final CommitDto commit = new CommitDto(
                new Revision(42), new Author("Alice", "alice@example.com"),
                new CommitMessageDto("summary", "detail", Markup.MARKDOWN),
                1789516800000L, UPSTREAM_COMMIT_ID);

        final JsonNode json = Jackson.valueToTree(commit);
        assertThat(json.has("commitId")).isFalse();
        assertThat(json.get("upstreamCommitId").textValue()).isEqualTo(UPSTREAM_COMMIT_ID);
    }

    @Test
    void rejectsNumericPushedAt() throws Exception {
        final JsonNode json = Jackson.readTree(commitJson(true, true));
        ((ObjectNode) json).put("pushedAt", 1);

        assertThatThrownBy(() -> Jackson.treeToValue(json, CommitDto.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    void ignoresUnknownFieldsFromNewerServers() throws Exception {
        final String json = commitJson(true, true);
        final CommitDto commit = Jackson.readValue(json.substring(0, json.length() - 1) +
                                                   ",\"futureField\":true}", CommitDto.class);

        assertThat(commit.upstreamCommitId()).isEqualTo(UPSTREAM_COMMIT_ID);
    }

    @Test
    void ignoresUnknownAuthorFieldsFromNewerServers() throws Exception {
        final CommitDto commit = Jackson.readValue(commitJson(true, true, true), CommitDto.class);

        assertThat(commit.author().name()).isEqualTo("Alice");
        assertThat(commit.author().email()).isEqualTo("alice@example.com");
    }

    private static String commitJson(boolean withCommitId, boolean withUpstreamCommitId) {
        return commitJson(withCommitId, withUpstreamCommitId, false);
    }

    private static String commitJson(boolean withCommitId, boolean withUpstreamCommitId,
                                     boolean withFutureAuthorField) {
        return '{' +
               "\"revision\":42," +
               "\"author\":{\"name\":\"Alice\",\"email\":\"alice@example.com\"" +
               (withFutureAuthorField ? ",\"futureAuthorField\":true" : "") +
               "}," +
               "\"commitMessage\":{\"summary\":\"summary\",\"detail\":\"detail\"," +
               "\"markup\":\"MARKDOWN\"}," +
               "\"pushedAt\":\"2026-09-16T00:00:00Z\"" +
               (withCommitId ? ",\"commitId\":\"" + LEGACY_COMMIT_ID + "\"" : "") +
               (withUpstreamCommitId ? ",\"upstreamCommitId\":\"" + UPSTREAM_COMMIT_ID + "\"" : "") +
               '}';
    }
}
