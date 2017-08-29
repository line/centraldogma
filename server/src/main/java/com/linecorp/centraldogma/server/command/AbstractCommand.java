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

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

abstract class AbstractCommand<T> implements Command<T> {

    private final CommandType type;

    protected AbstractCommand(CommandType type) {
        this.type = requireNonNull(type, "type");
    }

    @Override
    public final CommandType type() {
        return type;
    }

    @Override
    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(this instanceof AbstractCommand)) {
            return false;
        }

        final AbstractCommand<?> that = (AbstractCommand<?>) obj;
        return type == that.type;
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    @Override
    public final String toString() {
        return toStringHelper().toString();
    }

    MoreObjects.ToStringHelper toStringHelper() {
        return MoreObjects.toStringHelper(this).add("type", type);
    }
}
