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

package com.linecorp.centraldogma.server.admin_v2.authentication;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.util.List;

import org.hibernate.validator.constraints.NotBlank;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

import com.linecorp.centraldogma.common.Util;

public class User implements Serializable {

    private static final long serialVersionUID = -5429782019985526549L;

    @NotBlank
    private String login;
    private String name;
    private String email;
    private List<String> roles;

    public User() {}

    public User(String login, List<String> roles) {
        requireNonNull(roles, "roles");

        if (Strings.isNullOrEmpty(login)) {
            throw new IllegalArgumentException("login");
        }

        if (Util.isValidEmailAddress(login)) {
            email = this.login = login;
            name = login.substring(0, login.indexOf('@'));
        } else {
            name = this.login = login;
            email = login + "@linecorp.com";
        }

        this.roles = roles;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
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
                          .add("login", login)
                          .add("name", name)
                          .add("email", email)
                          .add("roles", roles)
                          .toString();
    }
}
