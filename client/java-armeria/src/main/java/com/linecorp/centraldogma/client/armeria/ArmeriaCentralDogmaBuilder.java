/*
 * Copyright 2019 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.UnknownHostException;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.encoding.HttpDecodingClient;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.internal.CsrfToken;

public final class ArmeriaCentralDogmaBuilder
        extends AbstractArmeriaCentralDogmaBuilder<ArmeriaCentralDogmaBuilder> {

    private String accessToken = CsrfToken.ANONYMOUS;

    /**
     * Sets the access token to use when authenticating a client.
     */
    public ArmeriaCentralDogmaBuilder accessToken(String accessToken) {
        requireNonNull(accessToken, "accessToken");
        checkArgument(!accessToken.isEmpty(), "accessToken is empty.");
        this.accessToken = accessToken;
        return this;
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance.
     *
     * @throws UnknownHostException if failed to resolve the host names from the DNS servers
     */
    public CentralDogma build() throws UnknownHostException {
        final Endpoint endpoint = endpoint();
        final String scheme = "none+" + (isUseTls() ? "https" : "http") + "://";
        final String uri = scheme + endpoint.authority();
        final ClientBuilder builder = new ClientBuilder(uri);
        clientConfigurator().configure(builder);
        builder.factory(clientFactory());
        builder.decorator(HttpDecodingClient.newDecorator());
        return new ArmeriaCentralDogma(clientFactory().eventLoopGroup(),
                                       builder.build(HttpClient.class), accessToken);
    }
}
