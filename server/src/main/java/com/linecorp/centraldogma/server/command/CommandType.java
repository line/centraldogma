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

package com.linecorp.centraldogma.server.command;

import com.linecorp.centraldogma.common.Revision;

/**
 * Types of a {@link Command}.
 */
public enum CommandType {
    CREATE_PROJECT(Void.class),
    REMOVE_PROJECT(Void.class),
    UNREMOVE_PROJECT(Void.class),
    CREATE_REPOSITORY(Void.class),
    REMOVE_REPOSITORY(Void.class),
    UNREMOVE_REPOSITORY(Void.class),
    NORMALIZING_PUSH(CommitResult.class),
    TRANSFORM(CommitResult.class),
    PUSH(Revision.class),
    SAVE_NAMED_QUERY(Void.class),
    REMOVE_NAMED_QUERY(Void.class),
    SAVE_PLUGIN(Void.class),
    REMOVE_PLUGIN(Void.class),
    CREATE_SESSION(Void.class),
    REMOVE_SESSION(Void.class),
    PURGE_PROJECT(Void.class),
    PURGE_REPOSITORY(Void.class),
    UPDATE_SERVER_STATUS(Void.class),
    UPDATE_REPOSITORY_STATUS(Void.class),
    // The result type of FORCE_PUSH is Object because it can be any type.
    FORCE_PUSH(Object.class);

    /**
     * The type of an object which is returned as a result after executing the command.
     */
    private final Class<?> resultType;

    CommandType(Class<?> resultType) {
        this.resultType = resultType;
    }

    /**
     * Returns the result type of the command.
     */
    public Class<?> resultType() {
        return resultType;
    }
}
