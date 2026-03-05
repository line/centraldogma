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

package com.linecorp.centraldogma.common;

import static com.google.common.base.Preconditions.checkArgument;

import org.jspecify.annotations.Nullable;

import com.google.common.base.MoreObjects;

final class NoContentChange extends AbstractChange<Void> {

    NoContentChange(String path, ChangeType type) {
        super(path, type);
        checkArgument(type.contentType() == Void.class,
                      "type.contentType() must be Void.class");
    }

    @Nullable
    @Override
    public Void content() {
        return null;
    }

    @Nullable
    @Override
    public String rawContent() {
        return null;
    }

    @Nullable
    @Override
    public String contentAsText() {
        return null;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("type", type())
                          .add("path", path())
                          .toString();
    }
}
