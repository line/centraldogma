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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.server.CorsConfig;

/**
 * Configuration for allowed redirect URIs.
 */
public final class AllowedUrisConfig {

    /**
     * Creates a new instance from the specified {@link CorsConfig}.
     */
    public static AllowedUrisConfig of(@Nullable CorsConfig corsConfig) {
        if (corsConfig == null) {
            return new AllowedUrisConfig(ImmutableList.of(), false);
        }
        final List<String> allowedOrigins = corsConfig.allowedOrigins();
        if (allowedOrigins.contains("*")) {
            return new AllowedUrisConfig(ImmutableList.of(), true);
        }
        final ImmutableList.Builder<URI> builder = ImmutableList.builder();
        for (String allowedOrigin : allowedOrigins) {
            try {
                builder.add(new URI(allowedOrigin));
            } catch (URISyntaxException ignored) {
                // skip
            }
        }
        return new AllowedUrisConfig(builder.build(), false);
    }

    private final List<URI> allowedUris;
    private final boolean allowAllUris;

    /**
     * Creates a new instance.
     */
    public AllowedUrisConfig(List<URI> allowedUris, boolean allowAllUris) {
        this.allowedUris = allowedUris;
        this.allowAllUris = allowAllUris;
    }

    /**
     * Returns the list of allowed redirect URIs.
     */
    public List<URI> allowedUris() {
        return allowedUris;
    }

    /**
     * Returns whether all redirect URIs are allowed.
     */
    public boolean allowAllUris() {
        return allowAllUris;
    }

    /**
     * Returns {@code true} if the specified {@code returnTo} is allowed.
     */
    public boolean isAllowedRedirectUri(@Nullable String returnTo) {
        if (returnTo == null) {
            return false;
        }

        if (allowAllUris) {
            return true;
        }

        if (allowedUris.isEmpty()) {
            return false;
        }

        try {
            final URI toUri = new URI(returnTo);
            final String toHost = toUri.getHost();
            if (toHost == null) {
                return false;
            }

            for (URI allowedUri : allowedUris) {
                if (toHost.equalsIgnoreCase(allowedUri.getHost())) {
                    return true;
                }
            }
        } catch (URISyntaxException ignored) {
            return false;
        }

        return false;
    }
}
