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

package com.linecorp.centraldogma.server.auth.saml;

import java.io.Serializable;
import java.util.List;

/**
 * A class representing a SAML session.
 */
public class SamlSession implements Serializable {
    private final List<String> groups;

    /**
     * Creates a new instance of the `SamlSession` class with the specified list of groups.
     *
     * @param groups The list of groups for this session.
     */
    public SamlSession(List<String> groups) {
        this.groups = groups;
    }

    /**
     * Returns the list of groups for this session.
     *
     * @return The list of groups for this session.
     */
    public List<String> groups() {
        return groups;
    }
}
