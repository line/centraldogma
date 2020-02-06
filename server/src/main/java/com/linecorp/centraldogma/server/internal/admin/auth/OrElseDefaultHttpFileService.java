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
package com.linecorp.centraldogma.server.internal.admin.auth;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextWrapper;
import com.linecorp.armeria.server.file.FileService;

public final class OrElseDefaultHttpFileService implements HttpService {

    private final HttpService delegate;

    public OrElseDefaultHttpFileService(FileService fileService, String defaultPath) {
        requireNonNull(fileService, "fileService");
        requireNonNull(defaultPath, "defaultPath");
        // Always return '/index.html' if there is no entry on the requested path, in order to route
        // the request by 'react-router'.
        delegate = fileService.orElse(
                (ctx, req) -> fileService.serve(new DefaultHtmlServiceRequestContext(ctx, defaultPath), req));
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return delegate.serve(ctx, req);
    }

    // TODO(hyangtack) Use a better HTTP file service later which serves only one file.
    private static final class DefaultHtmlServiceRequestContext extends ServiceRequestContextWrapper {

        private final String defaultPath;

        private DefaultHtmlServiceRequestContext(ServiceRequestContext delegate, String defaultPath) {
            super(delegate);
            this.defaultPath = defaultPath;
        }

        @Override
        public String decodedMappedPath() {
            return defaultPath;
        }
    }
}
