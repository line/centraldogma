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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.internal.Jackson;

class RepositoryMetadataTest {

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void deserialize(boolean legacyPermissionsFormat) throws Exception {
        final RepositoryMetadata repositoryMetadata =
                Jackson.readValue(metadataString(legacyPermissionsFormat), RepositoryMetadata.class);
        assertThat(repositoryMetadata.id()).isEqualTo("minu-test");
        assertThat(repositoryMetadata.name()).isEqualTo("minu-test"); // id and name are the same.
        assertThat(repositoryMetadata.perRolePermissions())
                .isEqualTo(new PerRolePermissions(Permission.READ, null));
        assertThat(repositoryMetadata.perUserPermissions())
                .isEqualTo(ImmutableMap.of("foo@dogma.com", Permission.READ,
                                           "bar@dogma.com", Permission.WRITE));
        assertThat(repositoryMetadata.perTokenPermissions())
                .isEqualTo(ImmutableMap.of("goodman", Permission.READ));
        assertThat(repositoryMetadata.creation())
                .isEqualTo(new UserAndTimestamp("minu.song@dogma.com", "2024-08-19T02:47:23.370762417Z"));
    }

    private static String metadataString(boolean legacyPermissionsFormat) {
        final String permissions;
        if (legacyPermissionsFormat) {
            permissions = "  \"perUserPermissions\": {" +
                          "    \"foo@dogma.com\": [" +
                          "      \"READ\"" +
                          "    ]," +
                          "    \"bar@dogma.com\": [" +
                          "      \"READ\"," +
                          "      \"WRITE\"" +
                          "    ]" +
                          "  }," +
                          "  \"perTokenPermissions\": {" +
                          "    \"goodman\": [" +
                          "      \"READ\"" +
                          "    ]" +
                          "  },";
        } else {
            permissions = "  \"perUserPermissions\": {" +
                          "    \"foo@dogma.com\": \"READ\"," +
                          "    \"bar@dogma.com\": \"WRITE\"" +
                          "  }," +
                          "  \"perTokenPermissions\": {" +
                          "    \"goodman\": \"READ\"" +
                          "  },";
        }

        return '{' +
               "  \"name\": \"minu-test\"," +
               "  \"perRolePermissions\": {" +
               "    \"owner\": [" +
               "      \"READ\"," +
               "      \"WRITE\"" +
               "    ]," +
               "    \"member\": [\"READ\"]," +
               "    \"guest\": []" +
               "  }," +
               permissions +
               "  \"creation\": {" +
               "    \"user\": \"minu.song@dogma.com\"," +
               "    \"timestamp\": \"2024-08-19T02:47:23.370762417Z\"" +
               "  }" +
               '}';
    }
}
