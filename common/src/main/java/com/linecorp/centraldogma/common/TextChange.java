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
import static com.linecorp.centraldogma.internal.Util.validateFilePath;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.google.common.base.MoreObjects;

final class TextChange extends AbstractChange<String> {

    private final String content;

    TextChange(String path, ChangeType type, String content) {
        super(path, type);
        checkArgument(type.contentType() == String.class,
                      "type.contentType() must be String.class");
        validateFilePath(path, "path");

        this.content = requireNonNull(content, "content");
    }

    @Override
    public String content() {
        return content;
    }

    @Override
    public String rawContent() {
        return content;
    }

    @Override
    public String contentAsText() {
        return content;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TextChange)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final TextChange that = (TextChange) o;
        return content.equals(that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), content);
    }

    @Override
    public String toString() {
        final String contentString;
        if (content.length() > 128) {
            contentString = content.substring(0, 128) + "...(length: " + content.length() + ')';
        } else {
            contentString = content;
        }
        return MoreObjects.toStringHelper(this)
                          .add("type", type())
                          .add("path", path())
                          .add("content", contentString)
                          .toString();
    }
}
