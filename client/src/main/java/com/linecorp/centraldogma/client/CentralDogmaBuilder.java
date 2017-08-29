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
package com.linecorp.centraldogma.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;

/**
 * Central Dogma client builder.
 */
public class CentralDogmaBuilder {

    private ClientFactory clientFactory = ClientFactory.DEFAULT;
    private URI uri;
    private ArmeriaClientConfigurator clientConfigurator = cb -> {
    };

    /**
     * Sets the specified {@code uri} that connects to the specified {@code uri}.
     *
     * @param uri the URI of the Central Dogma server. e.g.
     *            {@code tbinary+http://example.com:36462/cd/thrift/v1}
     */
    public CentralDogmaBuilder uri(String uri) {
        this.uri = URI.create(requireNonNull(uri, "uri"));
        return this;
    }

    /**
     * Sets the specified {@code clientFactory} that will manage the connection.
     */
    public CentralDogmaBuilder clientFactory(ClientFactory clientFactory) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        return this;
    }

    /**
     * Sets the specified {@code configurator} to customize Armeria client component.
     */
    public CentralDogmaBuilder clientConfigurator(ArmeriaClientConfigurator clientConfigurator) {
        this.clientConfigurator = requireNonNull(clientConfigurator, "clientConfigurator");
        return this;
    }

    /**
     * Create a {@link CentralDogma} instance.
     */
    public CentralDogma build() {
        if (uri == null) {
            throw new IllegalStateException("uri not set");
        }
        final ClientBuilder builder = new ClientBuilder(uri)
                .factory(clientFactory)
                .decorator(RpcRequest.class, RpcResponse.class,
                           CentralDogmaClientTimeoutScheduler::new);
        clientConfigurator.configure(builder);
        return new DefaultCentralDogma(clientFactory, builder.build(CentralDogmaService.AsyncIface.class));
    }
}
