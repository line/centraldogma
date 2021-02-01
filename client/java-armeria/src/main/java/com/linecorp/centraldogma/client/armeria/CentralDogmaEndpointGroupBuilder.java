/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.centraldogma.client.Watcher;

/**
 * Builds a {@link CentralDogmaEndpointGroup} that retrieves the list of {@link Endpoint}s from an entry
 * in Central Dogma.
 *
 * @param <T> the type of the file in Central Dogma
 */
public final class CentralDogmaEndpointGroupBuilder<T> {

    private final Watcher<T> watcher;
    private final EndpointListDecoder<T> endpointListDecoder;
    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();

    CentralDogmaEndpointGroupBuilder(Watcher<T> watcher, EndpointListDecoder<T> endpointListDecoder) {
        this.watcher = requireNonNull(watcher, "watcher");
        this.endpointListDecoder = requireNonNull(endpointListDecoder, "endpointListDecoder");
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} that creates an {@link EndpointSelector}.
     */
    public CentralDogmaEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    /**
     * Returns a newly-created {@link CentralDogmaEndpointGroup} that retrieves the list {@link Endpoint}s
     * from an entry in Central Dogma.
     */
    public CentralDogmaEndpointGroup<T> build() {
        return new CentralDogmaEndpointGroup<>(selectionStrategy, watcher, endpointListDecoder);
    }
}
