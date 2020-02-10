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
package com.linecorp.centraldogma.client.armeria;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;

final class CompositeEndpointGroup extends DynamicEndpointGroup {

    private final List<EndpointGroup> groups;

    CompositeEndpointGroup(Iterable<EndpointGroup> groups, EndpointSelectionStrategy selectionStrategy) {
        super(requireNonNull(selectionStrategy, "selectionStrategy"));
        this.groups = ImmutableList.copyOf(requireNonNull(groups, "groups"));

        // Add the listener for all groups.
        this.groups.forEach(g -> g.addListener(this::onEndpointUpdate));

        // Set the initial list of Endpoints if possible.
        final Set<Endpoint> initialEndpoints = collectEndpoints();
        if (!initialEndpoints.isEmpty()) {
            setEndpoints(initialEndpoints);
        }
    }

    @VisibleForTesting
    List<EndpointGroup> groups() {
        return groups;
    }

    private void onEndpointUpdate(@SuppressWarnings("unused") List<Endpoint> unused) {
        setEndpoints(collectEndpoints());
    }

    private Set<Endpoint> collectEndpoints() {
        final ImmutableSortedSet.Builder<Endpoint> initialEndpointsBuilder = ImmutableSortedSet.naturalOrder();
        groups.forEach(g -> initialEndpointsBuilder.addAll(g.endpoints()));
        return initialEndpointsBuilder.build();
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        try {
            groups.forEach(EndpointGroup::close);
        } finally {
            future.complete(null);
        }
    }
}
