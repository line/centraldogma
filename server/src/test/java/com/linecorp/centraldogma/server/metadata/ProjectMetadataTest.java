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
package com.linecorp.centraldogma.server.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RepositoryStatus;
import com.linecorp.centraldogma.internal.Jackson;

class ProjectMetadataTest {

    @Test
    void deserializeWithTokensOnly() throws Exception {
        final String json = buildJsonWithTokensOnly();
        final ProjectMetadata metadata = Jackson.readValue(json, ProjectMetadata.class);

        validateProjectMetadata(metadata);

        // Verify that appIds contains the token data
        assertThat(metadata.appIds()).hasSize(1);
        assertThat(metadata.appIds()).containsKey("app-token-1");

        final AppIdentityRegistration token = metadata.appIds().get("app-token-1");
        assertThat(token.id()).isEqualTo("app-token-1");
        assertThat(token.role()).isEqualTo(ProjectRole.MEMBER);
    }

    @Test
    void deserializeWithAppIds() throws Exception {
        final String json = buildJsonWithAppIds();
        final ProjectMetadata metadata = Jackson.readValue(json, ProjectMetadata.class);

        validateProjectMetadata(metadata);

        // Verify that appIds contains the data
        assertThat(metadata.appIds()).hasSize(1);
        assertThat(metadata.appIds()).containsKey("app-id-1");

        final AppIdentityRegistration token = metadata.appIds().get("app-id-1");
        assertThat(token.id()).isEqualTo("app-id-1");
        assertThat(token.role()).isEqualTo(ProjectRole.OWNER);
    }

    @Test
    void serialize() throws Exception {
        final UserAndTimestamp creation = UserAndTimestamp.of(Author.SYSTEM);
        final ProjectMetadata metadata = new ProjectMetadata(
                "test-project",
                ImmutableMap.of("repo1",
                                new RepositoryMetadata("repo1",
                                                       new Roles(ProjectRoles.of(null, null), ImmutableMap.of(),
                                                                 null, ImmutableMap.of()),
                                                       creation,
                                                       null, RepositoryStatus.ACTIVE)),
                ImmutableMap.of("user1@example.com",
                                new Member("user1@example.com", ProjectRole.MEMBER, creation)
                ),
                null,
                ImmutableMap.of("app-id-1",
                                new AppIdentityRegistration("app-id-1", ProjectRole.MEMBER, creation)
                ),
                new UserAndTimestamp(User.SYSTEM.id()),
                null);

        final String json = Jackson.writeValueAsString(metadata);

        // Verify JSON contains "appIds" field
        assertThat(json).contains("\"appIds\"");
        assertThat(json).contains("\"app-id-1\"");

        // Deserialize and verify
        final ProjectMetadata deserialized = Jackson.readValue(json, ProjectMetadata.class);
        assertThat(deserialized).isEqualTo(metadata);
    }

    @Test
    void roundTripWithTokensOnly() throws Exception {
        final String originalJson = buildJsonWithTokensOnly();
        final ProjectMetadata metadata = Jackson.readValue(originalJson, ProjectMetadata.class);

        // Serialize
        final String serializedJson = Jackson.writeValueAsString(metadata);

        // Deserialize again
        final ProjectMetadata deserializedMetadata = Jackson.readValue(serializedJson, ProjectMetadata.class);

        // Should be equal
        assertThat(deserializedMetadata).isEqualTo(metadata);
        assertThat(deserializedMetadata.appIds()).isEqualTo(metadata.appIds());
    }

    private static String buildJsonWithTokensOnly() {
        return '{' +
               "  \"name\": \"test-project\"," +
               "  \"repos\": {" +
               "    \"repo1\": {" +
               "      \"name\": \"repo1\"," +
               "      \"roles\": {" +
               "        \"projects\": {}," +
               "        \"users\": {}," +
               "        \"tokens\": {}" +
               "      }," +
               "      \"creation\": {" +
               "        \"user\": \"System\"," +
               "        \"timestamp\": \"2025-01-01T00:00:00Z\"" +
               "      }" +
               "    }" +
               "  }," +
               "  \"members\": {" +
               "    \"user1@example.com\": {" +
               "      \"login\": \"user1@example.com\"," +
               "      \"role\": \"MEMBER\"," +
               "      \"creation\": {" +
               "        \"user\": \"System\"," +
               "        \"timestamp\": \"2025-01-01T00:00:00Z\"" +
               "      }" +
               "    }" +
               "  }," +
               "  \"tokens\": {" +
               "    \"app-token-1\": {" +
               "      \"appId\": \"app-token-1\"," +
               "      \"role\": \"MEMBER\"," +
               "      \"creation\": {" +
               "        \"user\": \"System\"," +
               "        \"timestamp\": \"2025-01-01T00:00:00Z\"" +
               "      }" +
               "    }" +
               "  }," +
               "  \"creation\": {" +
               "    \"user\": \"System\"," +
               "    \"timestamp\": \"2025-01-01T00:00:00Z\"" +
               "  }" +
               '}';
    }

    private static String buildJsonWithAppIds() {
        return '{' +
               "  \"name\": \"test-project\"," +
               "  \"repos\": {" +
               "    \"repo1\": {" +
               "      \"name\": \"repo1\"," +
               "      \"roles\": {" +
               "        \"projects\": {}," +
               "        \"users\": {}," +
               "        \"appIds\": {}" +
               "      }," +
               "      \"creation\": {" +
               "        \"user\": \"System\"," +
               "        \"timestamp\": \"2025-01-01T00:00:00Z\"" +
               "      }" +
               "    }" +
               "  }," +
               "  \"members\": {" +
               "    \"user1@example.com\": {" +
               "      \"login\": \"user1@example.com\"," +
               "      \"role\": \"MEMBER\"," +
               "      \"creation\": {" +
               "        \"user\": \"System\"," +
               "        \"timestamp\": \"2025-01-01T00:00:00Z\"" +
               "      }" +
               "    }" +
               "  }," +
               "  \"appIds\": {" +
               "    \"app-id-1\": {" +
               "      \"appId\": \"app-id-1\"," +
               "      \"role\": \"OWNER\"," +
               "      \"creation\": {" +
               "        \"user\": \"System\"," +
               "        \"timestamp\": \"2025-01-01T00:00:00Z\"" +
               "      }" +
               "    }" +
               "  }," +
               "  \"creation\": {" +
               "    \"user\": \"System\"," +
               "    \"timestamp\": \"2025-01-01T00:00:00Z\"" +
               "  }" +
               '}';
    }

    private static void validateProjectMetadata(ProjectMetadata metadata) {
        assertThat(metadata.name()).isEqualTo("test-project");
        assertThat(metadata.repos()).hasSize(1);
        assertThat(metadata.repos()).containsKey("repo1");
        assertThat(metadata.members()).hasSize(1);
        assertThat(metadata.members()).containsKey("user1@example.com");
    }
}
