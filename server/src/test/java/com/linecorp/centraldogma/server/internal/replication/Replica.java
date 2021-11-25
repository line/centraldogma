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
package com.linecorp.centraldogma.server.internal.replication;

import static com.google.common.base.Preconditions.checkState;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.curator.test.InstanceSpec;

import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.server.ZooKeeperServerConfig;
import com.linecorp.centraldogma.server.command.AbstractCommandExecutor;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.PerRolePermissions;
import com.linecorp.centraldogma.server.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.metadata.UserAndTimestamp;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.MeterRegistry;

final class Replica {
    private final ZooKeeperCommandExecutor commandExecutor;
    private final Function<Command<?>, CompletableFuture<?>> delegate;
    private final File dataDir;
    private final MeterRegistry meterRegistry;
    private final CompletableFuture<Void> startFuture;

    Replica(InstanceSpec spec, Map<Integer, ZooKeeperServerConfig> servers,
            Function<Command<?>, CompletableFuture<?>> delegate,
            @Nullable QuotaConfig writeQuota, boolean start) {
        this.delegate = delegate;

        dataDir = spec.getDataDirectory();
        meterRegistry = PrometheusMeterRegistries.newRegistry();

        final int id = spec.getServerId();
        final ZooKeeperReplicationConfig zkCfg = new ZooKeeperReplicationConfig(id, servers);

        commandExecutor = new ZooKeeperCommandExecutor(zkCfg, dataDir, new AbstractCommandExecutor(null, null) {
            @Override
            public int replicaId() {
                return id;
            }

            @Override
            public void setWriteQuota(String projectName, String repoName, QuotaConfig writeQuota) {}

            @Override
            protected void doStart(@Nullable Runnable onTakeLeadership,
                                   @Nullable Runnable onReleaseLeadership) {}

            @Override
            protected void doStop(@Nullable Runnable onReleaseLeadership) {}

            @Override
            @SuppressWarnings("unchecked")
            protected <T> CompletableFuture<T> doExecute(Command<T> command) {
                return (CompletableFuture<T>) delegate.apply(command);
            }
        }, meterRegistry, mock(ProjectManager.class), writeQuota, null, null);
        commandExecutor.setMetadataService(mockMetaService());

        startFuture = start ? commandExecutor.start() : null;
    }

    void awaitStartup() {
        checkState(startFuture != null);
        startFuture.join();
    }

    long localRevision() {
        return await().ignoreExceptions().until(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(new File(dataDir, "last_revision"))))) {
                return Long.parseLong(br.readLine());
            }
        }, Objects::nonNull);
    }

    private static MetadataService mockMetaService() {
        final MetadataService mds = mock(MetadataService.class);
        final RepositoryMetadata repoMeta =
                new RepositoryMetadata("", UserAndTimestamp.of(Author.SYSTEM), PerRolePermissions.ofDefault());
        lenient().when(mds.getRepo(anyString(), anyString()))
                 .thenReturn(CompletableFuture.completedFuture(repoMeta));
        return mds;
    }

    boolean existsLocalRevision() {
        return Files.isReadable(new File(dataDir, "last_revision").toPath());
    }

    ZooKeeperCommandExecutor commandExecutor() {
        return commandExecutor;
    }

    Function<Command<?>, CompletableFuture<?>> delegate() {
        return delegate;
    }

    MeterRegistry meterRegistry() {
        return meterRegistry;
    }
}
