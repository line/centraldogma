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
    void nullAnonymous() throws Exception {
        final PerRolePermissions perRolePermissions = Jackson.readValue("{\"owner\": [" +
                                                                        "    \"READ\"," +
                                                                        "    \"WRITE\"" +
                                                                        "  ]," +
                                                                        "  \"member\": []," +
                                                                        "  \"guest\": []}",
                                                                        PerRolePermissions.class);
        assertThat(perRolePermissions.owner()).containsExactlyInAnyOrder(Permission.READ, Permission.WRITE);
        assertThat(perRolePermissions.member()).isEmpty();
        assertThat(perRolePermissions.guest()).isEmpty();
        assertThat(perRolePermissions.anonymous()).isEmpty();
    }
}
