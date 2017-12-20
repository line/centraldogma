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

import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.GracefulShutdownTimeout;
import com.linecorp.centraldogma.server.MirroringService;

import io.netty.util.NetUtil;

/**
 * JUnit {@link Rule} that starts an embedded {@link com.linecorp.centraldogma.server.CentralDogma} server.
 *
 * <pre>{@code
 * > public class MyTest {
 * >     @ClassRule
 * >     public final CentralDogmaRule rule = new CentralDogmaRule();
 * >
 * >     @Test
 * >     public void test() throws Exception {
 * >         CentralDogma dogma = rule.client();
 * >         dogma.push(...).join();
 * >         ...
 * >     }
 * > }
 * }</pre>
 */
public class CentralDogmaRule extends TemporaryFolder {

    private static final ServerPort TEST_PORT =
            new ServerPort(new InetSocketAddress(NetUtil.LOCALHOST4, 0), SessionProtocol.HTTP);

    private com.linecorp.centraldogma.server.CentralDogma dogma;
    private CentralDogma client;
    private HttpClient httpClient;

    /**
     * Returns the server.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public final com.linecorp.centraldogma.server.CentralDogma dogma() {
        if (dogma == null) {
            throw new IllegalStateException("Central Dogma not available");
        }
        return dogma;
    }

    /**
     * Returns the {@link MirroringService} of the server.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public final MirroringService mirroringService() {
        return dogma().mirroringService().get();
    }

    /**
     * Returns the client.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public final CentralDogma client() {
        if (client == null) {
            throw new IllegalStateException("Central Dogma client not available");
        }
        return client;
    }

    /**
     * Returns the HTTP client.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public final HttpClient httpClient() {
        if (httpClient == null) {
            throw new IllegalStateException("Central Dogma client not available");
        }
        return httpClient;
    }

    /**
     * Starts an embedded server with {@link #start()} and calls {@link #scaffold(CentralDogma)}.
     */
    @Override
    protected final void before() throws Throwable {
        super.before();
        start();
        scaffold(client);
    }

    /**
     * Creates a new server, configures it with {@link #configure(CentralDogmaBuilder)} and starts the server.
     * Note that you don't need to call this method if you did not stop the server with {@link #stop()},
     * because the server is automatically started up by JUnit.
     */
    public final void start() {
        final CentralDogmaBuilder builder = new CentralDogmaBuilder(getRoot())
                .port(TEST_PORT)
                .webAppEnabled(false)
                .mirroringEnabled(false)
                .gracefulShutdownTimeout(new GracefulShutdownTimeout(0, 0));

        configure(builder);

        dogma = builder.build();
        dogma.start();

        final InetSocketAddress serverAddress = dogma.activePort().get().localAddress();
        client = CentralDogma.forHost(serverAddress.getHostString(), serverAddress.getPort());
        httpClient = HttpClient.of("h2c://" + serverAddress.getHostString() + ':' + serverAddress.getPort());
    }

    /**
     * Override this method to configure the server.
     */
    protected void configure(CentralDogmaBuilder builder) {}

    /**
     * Override this method to perform the initial updates on the server, such as creating a repository and
     * populating sample data.
     */
    protected void scaffold(CentralDogma client) {}

    /**
     * Stops the server and deletes the temporary files created by the server.
     */
    @Override
    protected final void after() {
        stop();
    }

    /**
     * Stops the server and deletes the temporary files created by the server. Note that you don't usually need
     * to call this method manually because the server is automatically stopped at the end by JUnit.
     */
    public final void stop() {
        final com.linecorp.centraldogma.server.CentralDogma dogma = this.dogma;
        this.dogma = null;
        client = null;
        httpClient = null;

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
