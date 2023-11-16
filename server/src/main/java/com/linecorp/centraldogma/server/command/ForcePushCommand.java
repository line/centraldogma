/*
 * Copyright 2023 LINE Corporation
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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

public final class ForcePushCommand<T> extends AdministrativeCommand<T> {

    private final Command<T> delegate;

    @JsonCreator
    ForcePushCommand(@JsonProperty("delegate") Command<T> delegate) {
        super(CommandType.FORCE_PUSH, requireNonNull(delegate, "delegate").timestamp(), delegate.author());
        this.delegate = delegate;
    }

    @JsonProperty("delegate")
    public Command<T> delegate() {
        return delegate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ForcePushCommand)) {
            return false;
        }
        final ForcePushCommand<?> that = (ForcePushCommand<?>) o;
        return super.equals(that) && delegate.equals(that.delegate);
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + delegate.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper().add("delegate", delegate);
    }
}
