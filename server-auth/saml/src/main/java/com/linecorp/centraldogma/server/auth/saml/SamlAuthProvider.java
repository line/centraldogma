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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.saml.SamlServiceProvider;
import com.linecorp.centraldogma.server.auth.AuthProvider;
import com.linecorp.centraldogma.server.auth.AuthProviderParameters;

/**
 * OpenSAML based {@link AuthProvider} implementation.
 */
public class SamlAuthProvider implements AuthProvider {

    private final SamlServiceProvider sp;
    private final AuthProviderParameters parameters;

    SamlAuthProvider(SamlServiceProvider sp, AuthProviderParameters parameters) {
        this.sp = requireNonNull(sp, "sp");
        this.parameters = requireNonNull(parameters, "parameters");
    }

    @Override
    public HttpService webLoginService() {
        // TODO(minwoox): Add SamlLoginInitiatingService to Armeria.
        final HttpService service = (ctx, req) -> HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        return service.decorate(sp.newSamlDecorator());
    }

    @Override
    public Iterable<HttpServiceWithRoutes> moreServices() {
        return ImmutableList.of(sp.newSamlService());
    }

    @Override
    public AuthProviderParameters parameters() {
        return parameters;
    }
}
