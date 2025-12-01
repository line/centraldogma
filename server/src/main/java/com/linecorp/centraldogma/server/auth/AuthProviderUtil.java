/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.auth;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;

final class AuthProviderUtil {

    static HttpService loginOrLogoutService(AllowedUrisConfig allowedUrisConfig, String builtinWebPath) {
        requireNonNull(allowedUrisConfig, "allowedUrisConfig");
        return (ctx, req) -> {
            // Redirect to the default page: /link/auth/login -> /web/auth/login
            final String returnTo = ctx.queryParam("return_to");
            if (allowedUrisConfig.isAllowedRedirectUri(returnTo)) {
                return HttpResponse.ofRedirect(HttpStatus.MOVED_PERMANENTLY, returnTo + builtinWebPath);
            }

            return HttpResponse.ofRedirect(HttpStatus.MOVED_PERMANENTLY, builtinWebPath);
        };
    }

    private AuthProviderUtil() {}
}
