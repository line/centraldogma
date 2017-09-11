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

package com.linecorp.centraldogma.server;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.file.HttpFileService;
import com.linecorp.armeria.server.file.HttpVfs.ByteArrayEntry;
import com.linecorp.armeria.server.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftCallService;
import com.linecorp.centraldogma.common.Jackson;
import com.linecorp.centraldogma.server.admin_v2.service.ProjectService;
import com.linecorp.centraldogma.server.admin_v2.service.RepositoryService;
import com.linecorp.centraldogma.server.admin_v2.service.UserService;
import com.linecorp.centraldogma.server.admin_v2.util.RestfulJsonResponseConverter;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.ProjectInitializingCommandExecutor;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaExceptionTranslator;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaServiceImpl;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaTimeoutScheduler;
import com.linecorp.centraldogma.server.mirror.MirroringService;
import com.linecorp.centraldogma.server.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.project.ProjectManager;
import com.linecorp.centraldogma.server.replication.ReplicationMethod;
import com.linecorp.centraldogma.server.replication.ZooKeeperCommandExecutor;
import com.linecorp.centraldogma.server.replication.ZooKeeperReplicationConfig;

import io.netty.util.concurrent.DefaultThreadFactory;

public class CentralDogma {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogma.class);

    static {
        Jackson.registerModules(new SimpleModule().addSerializer(CacheStats.class, new CacheStatsSerializer()));
    }

    public static CentralDogma forConfig(File configFile) throws IOException {
        requireNonNull(configFile, "configFile");
        return new CentralDogma(Jackson.readValue(configFile, CentralDogmaConfig.class));
    }

    private final CentralDogmaConfig cfg;

    private volatile ProjectManager pm;
    private volatile Server server;
    private ExecutorService repositoryWorker;
    private CommandExecutor executor;
    private MirroringService mirroringService;

    CentralDogma(CentralDogmaConfig cfg) {
        this.cfg = requireNonNull(cfg, "cfg");
    }

    public Optional<ServerPort> activePort() {
        final Server server = this.server;
        return server != null ? server.activePort() : Optional.empty();
    }

    public Map<InetSocketAddress, ServerPort> activePorts() {
        final Server server = this.server;
        if (server != null) {
            return server.activePorts();
        } else {
            return Collections.emptyMap();
        }
    }

    public Optional<MirroringService> mirroringService() {
        return Optional.ofNullable(mirroringService);
    }

    // FIXME(trustin): Remove this from the public API.
    public Optional<CacheStats> cacheStats() {
        final ProjectManager pm = this.pm;
        if (pm == null) {
            return Optional.empty();
        }

        return Optional.of(pm.cacheStats());
    }

    public synchronized void start() {
        boolean success = false;
        ThreadPoolExecutor repositoryWorker = null;
        ProjectManager pm = null;
        MirroringService mirroringService = null;
        CommandExecutor executor = null;
        Server server = null;
        try {
            logger.info("Starting the Central Dogma ..");
            repositoryWorker = new ThreadPoolExecutor(
                    cfg.numRepositoryWorkers(), cfg.numRepositoryWorkers(),
                    60, TimeUnit.SECONDS, new LinkedTransferQueue<>(),
                    new DefaultThreadFactory("repository-worker", true));
            repositoryWorker.allowCoreThreadTimeOut(true);

            logger.info("Starting the project manager: {}", cfg.dataDir());

            pm = new DefaultProjectManager(cfg.dataDir(), repositoryWorker, cfg.cacheSpec());
            logger.info("Started the project manager: {}", pm);

            logger.info("Current settings:\n{}", cfg);

            mirroringService = new MirroringService(new File(cfg.dataDir(), "_mirrors"),
                                                    pm,
                                                    cfg.numMirroringThreads(),
                                                    cfg.maxNumFilesPerMirror(),
                                                    cfg.maxNumBytesPerMirror());

            logger.info("Starting the command executor ..");
            executor = startCommandExecutor(pm, mirroringService, repositoryWorker);
            logger.info("Started the command executor");

            logger.info("Starting the RPC server");
            server = startServer(pm, executor);
            logger.info("Started the RPC server at: {}", server.activePorts());
            logger.info("Started the Central Dogma successfully");
            success = true;
        } finally {
            if (success) {
                this.repositoryWorker = repositoryWorker;
                this.pm = pm;
                this.executor = executor;
                this.mirroringService = mirroringService;
                this.server = server;
            } else {
                stop(server, executor, mirroringService, pm, repositoryWorker);
            }
        }
    }

    private CommandExecutor startCommandExecutor(
            ProjectManager pm, MirroringService mirroringService, Executor repositoryWorker) {
        final CommandExecutor executor;
        final ReplicationMethod replicationMethod = cfg.replicationConfig().method();
        switch (replicationMethod) {
            case ZOOKEEPER:
                executor = newZooKeeperCommandExecutor(pm, repositoryWorker);
                break;
            case NONE:
                logger.info("No replication mechanism specified; entering standalone");
                executor = new StandaloneCommandExecutor(pm, repositoryWorker);
                break;
            default:
                throw new Error("unknown replication method: " + replicationMethod);
        }

        final CommandExecutor projInitExecutor = new ProjectInitializingCommandExecutor(executor);
        try {
            if (cfg.isMirroringEnabled()) {
                projInitExecutor.start(() -> {
                    logger.info("Starting the mirroring service ..");
                    mirroringService.start(projInitExecutor);
                    logger.info("Started the mirroring service");
                }, () -> {
                    logger.info("Stopping the mirroring service ..");
                    mirroringService.stop();
                    logger.info("Stopped the mirroring service");
                });
            } else {
                projInitExecutor.start(() -> logger.info(
                        "Not starting the mirroring service because it's disabled."), null);
            }
        } catch (Exception e) {
            logger.warn("Failed to start the command executor. Entering read-only.", e);
        }

        return projInitExecutor;
    }

    private Server startServer(ProjectManager pm, CommandExecutor executor) {

        final ServerBuilder sb = new ServerBuilder();
        for (ServerPort p: cfg.ports()) {
            sb.port(p);
        }

        cfg.numWorkers().ifPresent(
                numWorkers -> sb.workerGroup(EventLoopGroups.newEventLoopGroup(numWorkers), true));
        cfg.maxNumConnections().ifPresent(sb::maxNumConnections);
        cfg.idleTimeoutMillis().ifPresent(sb::idleTimeoutMillis);
        cfg.requestTimeoutMillis().ifPresent(sb::defaultRequestTimeoutMillis);
        cfg.maxFrameLength().ifPresent(sb::defaultMaxRequestLength);
        cfg.gracefulShutdownTimeout().ifPresent(
                t -> sb.gracefulShutdownTimeout(t.quietPeriodMillis(), t.timeoutMillis()));

        final CentralDogmaServiceImpl service =
                new CentralDogmaServiceImpl(pm, executor);

        sb.service("/cd/thrift/v1",
                     ThriftCallService.of(service)
                                      .decorate(CentralDogmaTimeoutScheduler::new)
                                      .decorate(CentralDogmaExceptionTranslator::new)
                                      .decorate(THttpService.newDecorator()));

        sb.service("/hostname", HttpFileService.forVfs(
                (path, encoding) -> new ByteArrayEntry(
                        path, MediaType.PLAIN_TEXT_UTF_8,
                        server.defaultHostname().getBytes(StandardCharsets.UTF_8))));

        sb.service("/cache_stats", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                    throws Exception {
                res.respond(HttpStatus.OK,
                            MediaType.JSON_UTF_8,
                            Jackson.writeValueAsPrettyString(pm.cacheStats()));
            }
        });

        sb.service("/monitor/l7check", new HttpHealthCheckService());
        sb.serviceUnder("/docs/", new DocService());

        if (cfg.isWebAppEnabled()) {
            configureWebAppV2(sb, pm, executor);
        }

        sb.serverListener(new ServerListenerAdapter() {
            @Override
            public void serverStopping(Server server) {
                service.serverStopping();
            }
        });

        final Server s = sb.build();
        s.start().join();
        return s;
    }

    private CommandExecutor newZooKeeperCommandExecutor(ProjectManager pm, Executor repositoryWorker) {

        final ZooKeeperReplicationConfig zkCfg = (ZooKeeperReplicationConfig) cfg.replicationConfig();
        final String replicaId = zkCfg.replicaId();
        logger.info("Using ZooKeeper-based replication mechanism; replicaId = {}", replicaId);

        // TODO(trustin): Provide a way to restart/reload the replicator
        //                so that we can recover from ZooKeeper maintenance automatically.
        final File revisionFile =
                new File(cfg.dataDir().getAbsolutePath() + File.separatorChar + "last_revision");
        return ZooKeeperCommandExecutor.builder()
                                       .replicaId(replicaId)
                                       .delegate(new StandaloneCommandExecutor(replicaId, pm, repositoryWorker))
                                       .numWorkers(zkCfg.numWorkers())
                                       .connectionString(zkCfg.connectionString())
                                       .timeoutMillis(zkCfg.timeoutMillis())
                                       .createPathIfNotExist(true)
                                       .path(zkCfg.pathPrefix())
                                       .maxLogCount(zkCfg.maxLogCount())
                                       .minLogAge(zkCfg.minLogAgeMillis(), TimeUnit.MILLISECONDS)
                                       .revisionFile(revisionFile)
                                       .build();
    }

    private static void configureWebAppV2(ServerBuilder sb, ProjectManager pm, CommandExecutor executor) {
        final UserService userService = new UserService(pm, executor);
        final ProjectService projectService = new ProjectService(pm, executor);
        final RepositoryService repositoryService = new RepositoryService(pm, executor);

        final Map<Class<?>, ResponseConverter> converters = ImmutableMap.of(
                Object.class, new RestfulJsonResponseConverter()  // Default converter
        );

        final String apiPathPrefix = "/api/";
        sb.annotatedService(apiPathPrefix, userService, converters)
          .annotatedService(apiPathPrefix, projectService, converters)
          .annotatedService(apiPathPrefix, repositoryService, converters)
          .serviceUnder("/", HttpFileService.forClassPath("webapp"));
    }

    public synchronized void stop() {
        if (server == null) {
            return;
        }

        final Server server = this.server;
        final CommandExecutor executor = this.executor;
        final MirroringService mirroringService = this.mirroringService;
        final ProjectManager pm = this.pm;
        final ExecutorService repositoryWorker = this.repositoryWorker;

        this.server = null;
        this.executor = null;
        this.mirroringService = null;
        this.pm = null;
        this.repositoryWorker = null;

        logger.info("Stopping the Central Dogma ..");
        if (!stop(server, executor, mirroringService, pm, repositoryWorker)) {
            logger.warn("Stopped the Central Dogma with failure");
        } else {
            logger.info("Stopped the Central Dogma successfully");
        }
    }

    private static boolean stop(
            Server server, CommandExecutor executor, MirroringService mirroringService,
            ProjectManager pm, ExecutorService repositoryWorker) {

        boolean success = true;
        try {
            if (server != null) {
                logger.info("Stopping the RPC server ..");
                server.stop().join();
                logger.info("Stopped the RPC server");
            }
        } catch (Exception e) {
            success = false;
            logger.warn("Failed to stop the RPC server:", e);
        } finally {
            try {
                if (executor != null) {
                    logger.info("Stopping the command executor ..");
                    executor.stop();
                    logger.info("Stopped the command executor");
                }
            } catch (Exception e) {
                success = false;
                logger.warn("Failed to stop the command executor:", e);
            } finally {
                try {
                    // Stop the mirroring service if the command executor did not stop it.
                    if (mirroringService != null && mirroringService.isStarted()) {
                        logger.info("Stopping the mirroring service not terminated by the command executor ..");
                        mirroringService.stop();
                        logger.info("Stopped the mirroring service");
                    }
                } catch (Exception e) {
                    success = false;
                    logger.warn("Failed to stop the mirroring service:", e);
                } finally {
                    try {
                        if (pm != null) {
                            logger.info("Stopping the project manager ..");
                            pm.close();
                            logger.info("Stopped the project manager");
                        }
                    } catch (Exception e) {
                        success = false;
                        logger.warn("Failed to stop the project manager:", e);
                    } finally {
                        if (repositoryWorker != null && !repositoryWorker.isTerminated()) {
                            logger.info("Stopping the repository worker ..");
                            boolean interruptLater = false;
                            while (!repositoryWorker.isTerminated()) {
                                repositoryWorker.shutdownNow();
                                try {
                                    repositoryWorker.awaitTermination(1, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    // Interrupt later.
                                    interruptLater = true;
                                }
                            }
                            logger.info("Stopped the repository worker");

                            if (interruptLater) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
            }
        }

        return success;
    }
}
