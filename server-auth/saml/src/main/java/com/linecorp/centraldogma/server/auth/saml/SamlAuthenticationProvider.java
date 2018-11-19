/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.centraldogma.server.auth.saml;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceWithPathMappings;
import com.linecorp.armeria.server.saml.SamlServiceProvider;
import com.linecorp.centraldogma.server.auth.AuthenticationProvider;

/**
 * OpenSAML based {@link AuthenticationProvider} implementation.
 */
public class SamlAuthenticationProvider implements AuthenticationProvider {

    private final SamlServiceProvider sp;

    SamlAuthenticationProvider(SamlServiceProvider sp) {
        this.sp = requireNonNull(sp, "sp");
    }

    @Override
    public Service<HttpRequest, HttpResponse> webLoginService() {
        // Should always redirect to the IdP because the browser cannot set a token to the request.
        return sp.newSamlDecorator().apply((ctx, req) -> HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Override
    public Iterable<ServiceWithPathMappings<HttpRequest, HttpResponse>> moreServices() {
        return ImmutableList.of(sp.newSamlService());
    }
}
