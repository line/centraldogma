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

import com.linecorp.centraldogma.internal.Jackson;

class PerRolePermissionsTest {

    @Test
    void deserialize() throws Exception {
        final String oldFormat = '{' +
                                 "  \"owner\": [" +
                                 "    \"READ\"," +
                                 "    \"WRITE\"" +
                                 "  ]," +
                                 "  \"member\": [" +
                                 "    \"READ\"," +
                                 "    \"WRITE\"" +
                                 "  ]," +
                                 "  \"guest\": [" +
                                 "    \"READ\"" +
                                 "  ]," +
                                 "  \"anonymous\": []" +
                                 '}';
        final PerRolePermissions oldFormatPermission = Jackson.readValue(oldFormat, PerRolePermissions.class);
        assertThat(oldFormatPermission.member()).containsExactlyInAnyOrder(Permission.READ, Permission.WRITE);
        assertThat(oldFormatPermission.guest()).containsExactly(Permission.READ);

        final String newFormat = '{' +
                                 "  \"member\": \"WRITE\"," +
                                 "  \"guest\": \"READ\"" +
                                 '}';
        final PerRolePermissions newFormatPermission = Jackson.readValue(newFormat, PerRolePermissions.class);
        assertThat(newFormatPermission.member()).containsExactlyInAnyOrder(Permission.READ, Permission.WRITE);
        assertThat(newFormatPermission.guest()).containsExactly(Permission.READ);

        final String newFormat2 = '{' +
                                  "  \"member\": null," +
                                  "  \"guest\": null" +
                                  '}';
        final PerRolePermissions newFormatPermission2 = Jackson.readValue(newFormat2, PerRolePermissions.class);
        assertThat(newFormatPermission2.member()).isEmpty();
        assertThat(newFormatPermission2.guest()).isEmpty();

        final String mixed = '{' +
                             "  \"owner\": [" +
                             "    \"READ\"," +
                             "    \"WRITE\"" +
                             "  ]," +
                             "  \"member\": [" +
                             "    \"READ\"," +
                             "    \"WRITE\"" +
                             "  ]," +
                             "  \"guest\": \"READ\"" +
                             '}';
        final PerRolePermissions mixedFormatPermission = Jackson.readValue(mixed, PerRolePermissions.class);
        assertThat(mixedFormatPermission.member()).containsExactlyInAnyOrder(Permission.READ, Permission.WRITE);
        assertThat(mixedFormatPermission.guest()).containsExactly(Permission.READ);
    }
}
