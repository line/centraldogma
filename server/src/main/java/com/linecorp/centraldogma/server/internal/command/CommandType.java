/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.command;

import com.linecorp.centraldogma.common.Revision;

public enum CommandType {
    CREATE_PROJECT(Void.class),
    REMOVE_PROJECT(Void.class),
    UNREMOVE_PROJECT(Void.class),
    CREATE_REPOSITORY(Void.class),
    REMOVE_REPOSITORY(Void.class),
    UNREMOVE_REPOSITORY(Void.class),
    PUSH(Revision.class),
    SAVE_NAMED_QUERY(Void.class),
    REMOVE_NAMED_QUERY(Void.class),
    SAVE_PLUGIN(Void.class),
    REMOVE_PLUGIN(Void.class);

    private final Class<?> resultType;

    CommandType(Class<?> resultType) {
        this.resultType = resultType;
    }

    public Class<?> resultType() {
        return resultType;
    }
}
