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

package com.linecorp.centraldogma.testing;

import java.net.InetSocketAddress;

import org.junit.rules.TemporaryFolder;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.GracefulShutdownTimeout;
import com.linecorp.centraldogma.server.MirroringService;

import io.netty.util.NetUtil;

public class CentralDogmaRule extends TemporaryFolder {

    private static final ServerPort TEST_PORT =
            new ServerPort(new InetSocketAddress(NetUtil.LOCALHOST4, 0), SessionProtocol.HTTP);

    private com.linecorp.centraldogma.server.CentralDogma dogma;
    private CentralDogma client;

    public com.linecorp.centraldogma.server.CentralDogma dogma() {
        if (dogma == null) {
            throw new IllegalStateException("Central Dogma not available");
        }
        return dogma;
    }

    public MirroringService mirroringService() {
        return dogma().mirroringService().get();
    }

    public CentralDogma client() {
        if (client == null) {
            throw new IllegalStateException("Central Dogma client not available");
        }
        return client;
    }

    @Override
    protected void before() throws Throwable {
        super.before();
        start();
        scaffold(client);
    }

    public void start() {
        final CentralDogmaBuilder builder = new CentralDogmaBuilder(getRoot())
                .port(TEST_PORT)
                .webAppEnabled(false)
                .mirroringEnabled(false)
                .gracefulShutdownTimeout(new GracefulShutdownTimeout(0, 0));

        configure(builder);

        dogma = builder.build();
        dogma.start();

        final InetSocketAddress serverAddress = dogma.activePort().get().localAddress();

        // TODO(trustin): Add a utility or a shortcut method in Armeria.
        final String host;
        if (NetUtil.isValidIpV6Address(serverAddress.getHostString())) {
            host = '[' + serverAddress.getHostString() + "]:" + serverAddress.getPort();
        } else {
            host = serverAddress.getHostString() + ':' + serverAddress.getPort();
        }

        client = CentralDogma.newClient("tbinary+http://" + host + "/cd/thrift/v1");
    }

    protected void configure(CentralDogmaBuilder builder) {}

    protected void scaffold(CentralDogma client) {}

    @Override
    protected void after() {
        stop();
    }

    public void stop() {
        final com.linecorp.centraldogma.server.CentralDogma dogma = this.dogma;
        this.dogma = null;
        client = null;

        if (dogma != null) {
            newCleanUpThread(dogma).start();
            Runtime.getRuntime().addShutdownHook(newCleanUpThread(dogma));
        }
    }

    private Thread newCleanUpThread(com.linecorp.centraldogma.server.CentralDogma dogma) {
        return new Thread(() -> {
            dogma.stop();
            super.after();
        });
    }
}
