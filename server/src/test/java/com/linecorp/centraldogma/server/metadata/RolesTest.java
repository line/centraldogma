/*
 * Copyright 2024 LINE Corporation
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

import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.internal.Jackson;

class RolesTest {

    @Test
    void deserializeWithTokensOnly() throws Exception {
        final String jsonWithTokensOnly = '{' +
                                          "  \"projects\": {" +
                                          "    \"member\": \"READ\"," +
                                          "    \"guest\": null" +
                                          "  }," +
                                          "  \"users\": {" +
                                          "    \"user1@example.com\": \"WRITE\"," +
                                          "    \"user2@example.com\": \"READ\"" +
                                          "  }," +
                                          "  \"tokens\": {" +
                                          "    \"token1\": \"READ\"," +
                                          "    \"token2\": \"WRITE\"" +
                                          "  }" +
                                          '}';

        final Roles roles = Jackson.readValue(jsonWithTokensOnly, Roles.class);

        assertThat(roles.projectRoles().member()).isEqualTo(RepositoryRole.READ);
        assertThat(roles.projectRoles().guest()).isNull();
        assertThat(roles.users()).isEqualTo(
                ImmutableMap.of("user1@example.com", RepositoryRole.WRITE,
                                "user2@example.com", RepositoryRole.READ));
        assertThat(roles.appIds()).isEqualTo(
                ImmutableMap.of("token1", RepositoryRole.READ,
                                "token2", RepositoryRole.WRITE));
    }

    @Test
    void deserializeWithAppIdsOnly() throws Exception {
        final String jsonWithAppIdsOnly = '{' +
                                          "  \"projects\": {" +
                                          "    \"member\": \"WRITE\"," +
                                          "    \"guest\": \"READ\"" +
                                          "  }," +
                                          "  \"users\": {" +
                                          "    \"admin@example.com\": \"WRITE\"" +
                                          "  }," +
                                          "  \"appIds\": {" +
                                          "    \"app1\": \"READ\"," +
                                          "    \"app2\": \"WRITE\"" +
                                          "  }" +
                                          '}';

        final Roles roles = Jackson.readValue(jsonWithAppIdsOnly, Roles.class);

        assertThat(roles.projectRoles().member()).isEqualTo(RepositoryRole.WRITE);
        assertThat(roles.projectRoles().guest()).isEqualTo(RepositoryRole.READ);
        assertThat(roles.users()).isEqualTo(
                ImmutableMap.of("admin@example.com", RepositoryRole.WRITE));
        assertThat(roles.appIds()).isEqualTo(
                ImmutableMap.of("app1", RepositoryRole.READ,
                                "app2", RepositoryRole.WRITE));
    }

    @Test
    void serializeToAppIds() throws Exception {
        final Roles roles = new Roles(
                ProjectRoles.of(RepositoryRole.READ, null),
                ImmutableMap.of("user@example.com", RepositoryRole.WRITE),
                null,
                ImmutableMap.of("app1", RepositoryRole.READ));

        final String json = Jackson.writeValueAsString(roles);

        assertThat(json).contains("\"appIds\"");
        assertThat(json).contains("\"app1\"");
        assertThat(json).doesNotContain("\"tokens\"");

        final Roles deserialized = Jackson.readValue(json, Roles.class);
        assertThat(deserialized.appIds()).isEqualTo(roles.appIds());
        assertThat(deserialized.users()).isEqualTo(roles.users());
        assertThat(deserialized.projectRoles()).isEqualTo(roles.projectRoles());
    }
}
