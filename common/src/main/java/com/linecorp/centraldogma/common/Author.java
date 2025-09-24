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

import static com.linecorp.centraldogma.internal.Util.TOKEN_EMAIL_SUFFIX;
import static com.linecorp.centraldogma.internal.Util.emailToUsername;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.centraldogma.internal.Util;

/**
 * An author of a {@link Commit}.
 */
public class Author {

    /**
     * The system author.
     */
    public static final Author SYSTEM = new Author("system", "system@localhost.localdomain");

    /**
     * The default author which is used when security is disabled.
     */
    public static final Author DEFAULT = new Author("user", "user@localhost.localdomain");

    /**
     * An unknown author.
     */
    public static final Author UNKNOWN = new Author("Unknown", "nobody@no.where");

    /**
     * Create a new {@link Author} with the {@code email}.
     * The {@link #name()} will be set to the username of the {@code email}.
     */
    public static Author ofEmail(String email) {
        return new Author(emailToUsername(email, "email"), email);
    }

    private final String name;
    private final String email;

    /**
     * Creates a new instance with the specified e-mail address.
     *
     * @deprecated Use {@link #ofEmail(String)}.
     */
    @Deprecated
    public Author(String email) {
        this(email, email);
    }

    /**
     * Creates a new instance with the specified name and e-mail address.
     */
    @JsonCreator
    public Author(@JsonProperty("name") String name,
                  @JsonProperty("email") String email) {

        this.name = requireNonNull(name, "name");
        this.email = Util.validateEmailAddress(email, "email");
    }

    /**
     * Returns the name of the author.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the e-mail address of the author.
     */
    @JsonProperty
    public String email() {
        return email;
    }

    /**
     * Returns {@code true} if this author is a token.
     */
    @JsonIgnore
    public boolean isToken() {
        return email().endsWith(TOKEN_EMAIL_SUFFIX);
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
        final Author author = (Author)o;
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
