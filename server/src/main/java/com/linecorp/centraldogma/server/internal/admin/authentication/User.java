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

package com.linecorp.centraldogma.server.internal.admin.authentication;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.internal.Util;

public class User implements Serializable {

    private static final long serialVersionUID = -5429782019985526549L;

    // TODO(hyangtack) Will change the word "role" to something other to avoid conflicting with project "role".
    // System-wide roles for a user. It is different from the role in a project.
    public static final List<String> USER_ROLE = ImmutableList.of("ROLE_USER");
    public static final List<String> ADMIN_ROLE = ImmutableList.of("ROLE_ADMIN");

    public static final User DEFAULT = new User("User@localhost.localdomain", USER_ROLE);
    public static final User ADMIN = new User("Admin@localhost.localdomain", ADMIN_ROLE);

    private String login;
    private String name;
    private String email;
    private List<String> roles;

    @JsonCreator
    public User(@JsonProperty("login") String login,
                @JsonProperty("name") String name,
                @JsonProperty("email") String email,
                @JsonProperty("roles") List<String> roles) {
        this.login = requireNonNull(login, "login");
        this.name = requireNonNull(name, "name");
        this.email = requireNonNull(email, "email");
        this.roles = ImmutableList.copyOf(requireNonNull(roles, "roles"));
    }

    public User(String login) {
        this(login, USER_ROLE);
    }

    private User(String login, List<String> roles) {
        requireNonNull(roles, "roles");

        if (Strings.isNullOrEmpty(login)) {
            throw new IllegalArgumentException("login");
        }

        if (Util.isValidEmailAddress(login)) {
            email = this.login = login;
            name = login.substring(0, login.indexOf('@'));
        } else {
            name = this.login = login;
            email = login + "@localhost.localdomain";
        }

        this.roles = roles;
    }

    @JsonProperty
    public String login() {
        return login;
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public String email() {
        return email;
    }

    @JsonProperty
    public List<String> roles() {
        return roles;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final User user = (User) o;
        return login.equals(user.login);
    }

    @Override
    public int hashCode() {
        return login.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("login", login())
                          .add("name", name())
                          .add("email", email())
                          .add("roles", roles())
                          .toString();
    }
}
