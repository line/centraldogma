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

package com.linecorp.centraldogma.common;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.centraldogma.internal.Util;

public class Author {

    public static final Author SYSTEM = new Author("System", "system@localhost.localdomain");
    public static final Author DEFAULT = new Author("User", "user@localhost.localdomain");
    public static final Author UNKNOWN = new Author("Unknown", "nobody@no.where");

    private final String name;
    private final String email;

    public Author(String email) {
        this(email, email);
    }

    @JsonCreator
    public Author(@JsonProperty("name") String name,
                  @JsonProperty("email") String email) {

        this.name = requireNonNull(name, "name");
        this.email = Util.validateEmailAddress(email, "email");
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public String email() {
        return email;
    }

    @Override
    public int hashCode() {
        return email.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Author)) {
            return false;
        }
        Author author = (Author)o;
        return email.equals(author.email);
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();

        buf.append(Util.simpleTypeName(this));
        buf.append("[\"");
        buf.append(name);
        buf.append("\" <");
        buf.append(email);
        buf.append(">]");

        return buf.toString();
    }
}
