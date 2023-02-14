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

package com.linecorp.centraldogma.server.internal.admin.auth;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.metadata.User;

import io.netty.util.AttributeKey;

/**
 * A utility class to manage a {@link User} and its {@link Author} instances of the current login user.
 */
public final class AuthUtil {

    @VisibleForTesting
    public static final AttributeKey<User> CURRENT_USER =
            AttributeKey.valueOf(AuthUtil.class, "CURRENT_USER");

    public static Author currentAuthor(ServiceRequestContext ctx) {
        final User user = ctx.attr(CURRENT_USER);
        assert user != null;
        return user == User.DEFAULT ? Author.DEFAULT
                                    : new Author(user.name(), user.email());
    }

    public static Author currentAuthor() {
        return currentAuthor(RequestContext.current());
    }

    public static User currentUser(ServiceRequestContext ctx) {
        return ctx.attr(CURRENT_USER);
    }

    public static User currentUser() {
        return currentUser(RequestContext.current());
    }

    public static void setCurrentUser(ServiceRequestContext ctx, User currentUser) {
        requireNonNull(ctx, "ctx");
        requireNonNull(currentUser, "currentUser");
        ctx.setAttr(CURRENT_USER, currentUser);
    }

    private AuthUtil() {}
}
