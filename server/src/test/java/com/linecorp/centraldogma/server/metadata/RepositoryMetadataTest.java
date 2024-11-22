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

import static com.linecorp.centraldogma.server.metadata.PerRolePermissions.NO_PERMISSION;
import static com.linecorp.centraldogma.server.metadata.PerRolePermissions.READ_ONLY;
import static com.linecorp.centraldogma.server.metadata.PerRolePermissions.READ_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.internal.Jackson;

class RepositoryMetadataTest {

    @Test
    void deserializeLegacyFormat() throws Exception {
        final String format = '{' +
                              "  \"name\": \"minu-test\"," +
                              "  \"perRolePermissions\": {" +
                              "    \"owner\": [" +
                              "      \"READ\"," +
                              "      \"WRITE\"" +
                              "    ]," +
                              "    \"member\": [\"READ\"]," +
                              "    \"guest\": []" +
                              "  }," +
                              "  \"perUserPermissions\": {" +
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
                              "  }," +
                              "  \"creation\": {" +
                              "    \"user\": \"minu.song@dogma.com\"," +
                              "    \"timestamp\": \"2024-08-19T02:47:23.370762417Z\"" +
                              "  }" +
                              '}';
        validate(Jackson.readValue(format, RepositoryMetadata.class));
    }

    @Test
    void deserializeNewFormat() throws Exception {
        final String format = '{' +
                              "  \"name\": \"minu-test\"," +
                              "  \"roles\": {" +
                              "    \"projects\": {" +
                              "      \"member\": \"READ\"," +
                              "      \"guest\": null" +
                              "    }," +
                              "    \"users\": {" +
                              "      \"foo@dogma.com\": \"READ\"," +
                              "      \"bar@dogma.com\": \"WRITE\"" +
                              "    }," +
                              "    \"tokens\": {" +
                              "      \"goodman\": \"READ\"" +
                              "    }" +
                              "  }," +
                              "  \"creation\": {" +
                              "    \"user\": \"minu.song@dogma.com\"," +
                              "    \"timestamp\": \"2024-08-19T02:47:23.370762417Z\"" +
                              "  }" +
                              '}';
        validate(Jackson.readValue(format, RepositoryMetadata.class));
    }

    private static void validate(RepositoryMetadata repositoryMetadata) {
        assertThat(repositoryMetadata.id()).isEqualTo("minu-test");
        assertThat(repositoryMetadata.name()).isEqualTo("minu-test"); // id and name are the same.
        assertThat(repositoryMetadata.perRolePermissions())
                .isEqualTo(new PerRolePermissions(READ_WRITE, READ_ONLY, NO_PERMISSION, null));
        assertThat(repositoryMetadata.perUserPermissions())
                .isEqualTo(ImmutableMap.of("foo@dogma.com", ImmutableList.of(Permission.READ),
                                           "bar@dogma.com",
                                           ImmutableList.of(Permission.READ, Permission.WRITE)));
        assertThat(repositoryMetadata.perTokenPermissions())
                .isEqualTo(ImmutableMap.of("goodman", ImmutableList.of(Permission.READ)));
        assertThat(repositoryMetadata.creation())
                .isEqualTo(new UserAndTimestamp("minu.song@dogma.com", "2024-08-19T02:47:23.370762417Z"));
    }
}
