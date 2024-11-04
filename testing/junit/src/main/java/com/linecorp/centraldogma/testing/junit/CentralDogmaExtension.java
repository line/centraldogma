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
package com.linecorp.centraldogma.testing.junit;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javax.annotation.Nullable;

import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.client.armeria.legacy.LegacyCentralDogmaBuilder;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.internal.CentralDogmaRuleDelegate;
import com.linecorp.centraldogma.testing.internal.TemporaryFolder;

/**
 * A JUnit {@link Extension} that starts an embedded Central Dogma server.
 *
 * <pre>{@code
 * > class MyTest {
 * >     @RegisterExtension
 * >     static final CentralDogmaExtension extension = new CentralDogmaExtension();
 * >
 * >     @Test
 * >     void test() throws Exception {
 * >         CentralDogma dogma = extension.client();
 * >         dogma.push(...).join();
 * >         ...
 * >     }
 * > }
 * }</pre>
 */
public class CentralDogmaExtension extends AbstractAllOrEachExtension {

    private final CentralDogmaRuleDelegate delegate;

    private final TemporaryFolder dataDir = new TemporaryFolder();

    /**
     * Creates a new instance with TLS disabled.
     */
    public CentralDogmaExtension() {
        this(false);
    }

    /**
     * Creates a new instance.
     */
    public CentralDogmaExtension(boolean useTls) {
        delegate = new CentralDogmaRuleDelegate(useTls) {
            @Override
            protected void configure(CentralDogmaBuilder builder) {
                CentralDogmaExtension.this.configure(builder);
            }

            @Override
            protected void configureClient(ArmeriaCentralDogmaBuilder builder) {
                CentralDogmaExtension.this.configureClient(builder);
            }

            @Override
            protected void configureClient(LegacyCentralDogmaBuilder builder) {
                CentralDogmaExtension.this.configureClient(builder);
            }

            @Override
            protected void configureHttpClient(WebClientBuilder builder) {
                CentralDogmaExtension.this.configureHttpClient(builder);
            }

            @Nullable
            @Override
            protected String accessToken() throws Exception {
                return CentralDogmaExtension.this.accessToken();
            }

            @Override
            protected void scaffold(CentralDogma client) {
                CentralDogmaExtension.this.scaffold(client);
            }
        };
    }

    @Override
    public void before(ExtensionContext context) throws Exception {
        dataDir.create();
        delegate.setUp(dataDir.getRoot().toFile());
    }

    @Override
    public void after(ExtensionContext context) throws Exception {
        stopAsync().whenComplete((unused1, unused2) -> {
            try {
                dataDir.delete();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Returns the {@link Path} to the server's data directory.
     *
     * @throws IllegalStateException if the data directory is not created yet
     */
    @UnstableApi
    public final Path dataDir() {
        return dataDir.getRoot();
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
        if (!dataDir.exists()) {
            try {
                dataDir.create();
            } catch (IOException e) {
                return CompletableFutures.exceptionallyCompletedFuture(e);
            }
        }

        return delegate.startAsync(dataDir.getRoot().toFile());
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
     * Returns the {@link ProjectManager} of the server.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public ProjectManager projectManager() {
        return delegate.projectManager();
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
     * Returns the blocking HTTP client.
     *
     * @throws IllegalStateException if Central Dogma did not start yet
     */
    public final BlockingWebClient blockingHttpClient() {
        return delegate.blockingHttpClient();
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
     * Override this method to configure the {@link WebClient} builder.
     */
    protected void configureHttpClient(WebClientBuilder builder) {}

    /**
     * Override this method to inject an access token to the clients.
     */
    @Nullable
    protected String accessToken() throws Exception {
        return null;
    }

    /**
     * Override this method to perform the initial updates on the server,
     * such as creating a repository and populating sample data.
     */
    protected void scaffold(CentralDogma client) {}
}
