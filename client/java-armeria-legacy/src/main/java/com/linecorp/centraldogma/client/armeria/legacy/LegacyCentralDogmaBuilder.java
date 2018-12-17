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
package com.linecorp.centraldogma.client.armeria.legacy;

import java.net.UnknownHostException;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.AbstractArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;

public class LegacyCentralDogmaBuilder extends AbstractArmeriaCentralDogmaBuilder<LegacyCentralDogmaBuilder> {
    /**
     * Returns a newly-created {@link CentralDogma} instance.
     *
     * @throws UnknownHostException if failed to resolve the host names from the DNS servers
     */
    public CentralDogma build() throws UnknownHostException {
        final Endpoint endpoint = endpoint();
        final String scheme = "tbinary+" + (isUseTls() ? "https" : "http") + "://";
        final String uri = scheme + endpoint.authority() + "/cd/thrift/v1";
        final ClientBuilder builder = new ClientBuilder(uri)
                .factory(clientFactory())
                .rpcDecorator(CentralDogmaClientTimeoutScheduler::new);
        clientConfigurator().configure(builder);

        builder.decorator((delegate, ctx, req) -> {
            if (!req.headers().contains(HttpHeaderNames.AUTHORIZATION)) {
                // To prevent CSRF attack, we add 'Authorization' header to every request.
                req.headers().set(HttpHeaderNames.AUTHORIZATION, "bearer " + CsrfToken.ANONYMOUS);
            }
            return delegate.execute(ctx, req);
        });
        return new LegacyCentralDogma(clientFactory(), builder.build(CentralDogmaService.AsyncIface.class));
    }
}
