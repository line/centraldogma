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

package com.linecorp.centraldogma.server.internal.metadata;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

import io.netty.util.AttributeKey;

/**
 * Injects the {@link MetadataService} instance into the attribute of the {@link ServiceRequestContext}.
 */
public final class MetadataServiceInjector extends SimpleDecoratingService<HttpRequest, HttpResponse> {

    public static Function<Service<HttpRequest, HttpResponse>,
            MetadataServiceInjector> newDecorator(MetadataService mds) {
        requireNonNull(mds, "mds");
        return service -> new MetadataServiceInjector(service, mds);
    }

    private static final AttributeKey<MetadataService> METADATA_SERVICE_ATTRIBUTE_KEY =
            AttributeKey.valueOf(MetadataServiceInjector.class, "METADATA");

    private final MetadataService mds;

    public MetadataServiceInjector(Service<HttpRequest, HttpResponse> delegate, MetadataService mds) {
        super(delegate);
        this.mds = requireNonNull(mds, "mds");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        ctx.attr(METADATA_SERVICE_ATTRIBUTE_KEY).set(mds);
        return delegate().serve(ctx, req);
    }

    /**
     * Returns the {@link MetadataService} instance from the specified {@link ServiceRequestContext}.
     */
    public static MetadataService getMetadataService(ServiceRequestContext ctx) {
        final MetadataService mds = ctx.attr(METADATA_SERVICE_ATTRIBUTE_KEY).get();
        if (mds != null) {
            return mds;
        }
        throw new IllegalStateException("No metadata service instance exists.");
    }
}
