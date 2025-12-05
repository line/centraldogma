/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.centraldogma.client;

import com.linecorp.centraldogma.common.QueryType;

class AbstractFileRequest<SELF extends AbstractFileRequest<SELF>> {

    private boolean viewRaw;

    /**
     * Sets whether to view the raw content of the file.
     * Default is {@code false} which means the content may be normalized.
     *
     * <p>Note that {@link QueryType#JSON_PATH} query cannot be used with raw view.
     */
    public SELF viewRaw(boolean viewRaw) {
        this.viewRaw = viewRaw;
        return self();
    }

    /**
     * Returns whether to view the raw content of the file.
     */
    boolean viewRaw() {
        return viewRaw;
    }

    @SuppressWarnings("unchecked")
    private SELF self() {
        return (SELF) this;
    }
}
