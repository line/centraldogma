/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.centraldogma.testing.junit4;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.client.armeria.legacy.LegacyCentralDogmaBuilder;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.testing.internal.CentralDogmaRuleDelegate;

/**
 * A JUnit {@link Rule} that starts an embedded Central Dogma server.
 *
 * <pre>{@code
 * > public class MyTest {
 * >     @ClassRule
 * >     public static final CentralDogmaRule rule = new CentralDogmaRule();
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

    private final CentralDogmaRuleDelegate delegate;

    /**
     * Creates a new instance with TLS disabled.
     */
    public CentralDogmaRule() {
        this(false);
    }

    /**
     * Creates a new instance.
     */
    public CentralDogmaRule(boolean useTls) {
        delegate = new CentralDogmaRuleDelegate(useTls) {
            @Override
            protected void configure(CentralDogmaBuilder builder) {
                CentralDogmaRule.this.configure(builder);
            }

            @Override
            protected void configureClient(ArmeriaCentralDogmaBuilder builder) {
                CentralDogmaRule.this.configureClient(builder);
            }

            @Override
            protected void configureClient(LegacyCentralDogmaBuilder builder) {
                CentralDogmaRule.this.configureClient(builder);
            }

            @Override
            protected void scaffold(CentralDogma client) {
                CentralDogmaRule.this.scaffold(client);
            }
        };
    }

    /**
     * Starts an embedded server and calls {@link #scaffold(CentralDogma)}.
     */
    @Override
    protected void before() throws Throwable {
        super.before();
        delegate.setUp(getRoot());
    }

    /**
     * Stops the server and deletes the temporary files created by the server.
     */
    @Override
    protected void after() {
        stopAsync().whenComplete((unused1, unused2) -> delete());
    }

    /**
     * Creates a new server, configures it with {@link #configure(CentralDogmaBuilder)} and starts the server.
     * Note that you don't need to call this method if you did not stop the server with {@link #stop()},
     * because the server is automatically started up by JUnit.
     */
    public final void start() {
        startAsync().join();
    }

    /**
     * Creates a new server, configures it with {@link #configure(CentralDogmaBuilder)},
     * and starts the server asynchronously.
     */
    public final CompletableFuture<Void> startAsync() {
        // Create the root folder first if it doesn't exist.
        try {
            getRoot();
        } catch (IllegalStateException unused) {
            try {
                create();
            } catch (IOException e) {
                return CompletableFutures.exceptionallyCompletedFuture(e);
            }
        }

        return delegate.startAsync(getRoot());
    }

    /**
     * Stops the server and deletes the temporary files created by the server. Note that you don't usually need
     * to call this method manually because the server is automatically stopped at the end by JUnit.
     */
    public final void stop() {
        stopAsync().join();
    }

    /**
     * Stops the server and deletes the temporary files created by the server. Note that you don't usually need
     * to call this method manually because the server is automatically stopped at the end by JUnit.
     */
    public final CompletableFuture<Void> stopAsync() {
        return delegate.stopAsync();
    }

    /**
     * Returns whether the server is running over TLS or not.
     */
    public boolean useTls() {
        return delegate.useTls();
    }

    /**
     * Returns the server.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public final com.linecorp.centraldogma.server.CentralDogma dogma() {
        return delegate.dogma();
    }

    /**
     * Returns the {@link MirroringService} of the server.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public final MirroringService mirroringService() {
        return delegate.mirroringService();
    }

    /**
     * Returns the HTTP-based {@link CentralDogma} client.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public final CentralDogma client() {
        return delegate.client();
    }

    /**
     * Returns the Thrift-based {@link CentralDogma} client.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public final CentralDogma legacyClient() {
        return delegate.legacyClient();
    }

    /**
     * Returns the HTTP client.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public final WebClient httpClient() {
        return delegate.httpClient();
    }

    /**
     * Returns the server address.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public final InetSocketAddress serverAddress() {
        return delegate.serverAddress();
    }

    /**
     * Override this method to configure the server.
     */
    protected void configure(CentralDogmaBuilder builder) {}

    /**
     * Override this method to configure the HTTP-based {@link CentralDogma} client builder.
     */
    protected void configureClient(ArmeriaCentralDogmaBuilder builder) {}

    /**
     * Override this method to configure the Thrift-based {@link CentralDogma} client builder.
     */
    protected void configureClient(LegacyCentralDogmaBuilder builder) {}

    /**
     * Override this method to perform the initial updates on the server,
     * such as creating a repository and populating sample data.
     */
    protected void scaffold(CentralDogma client) {}
}
