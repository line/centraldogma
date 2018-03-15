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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.initializeInternalProject;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.shiro.config.Ini;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.HttpAuthService;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.file.AbstractHttpVfs;
import com.linecorp.armeria.server.file.HttpFileService;
import com.linecorp.armeria.server.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.server.logging.AccessLogWriters;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftCallService;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;
import com.linecorp.centraldogma.server.internal.admin.authentication.CentralDogmaSecurityManager;
import com.linecorp.centraldogma.server.internal.admin.authentication.CsrfTokenAuthorizer;
import com.linecorp.centraldogma.server.internal.admin.authentication.LoginService;
import com.linecorp.centraldogma.server.internal.admin.authentication.LogoutService;
import com.linecorp.centraldogma.server.internal.admin.authentication.SessionTokenAuthorizer;
import com.linecorp.centraldogma.server.internal.admin.service.RepositoryService;
import com.linecorp.centraldogma.server.internal.admin.service.UserService;
import com.linecorp.centraldogma.server.internal.admin.util.RestfulJsonResponseConverter;
import com.linecorp.centraldogma.server.internal.api.AdministrativeService;
import com.linecorp.centraldogma.server.internal.api.ContentServiceV1;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService;
import com.linecorp.centraldogma.server.internal.api.ProjectServiceV1;
import com.linecorp.centraldogma.server.internal.api.RepositoryServiceV1;
import com.linecorp.centraldogma.server.internal.api.TokenService;
import com.linecorp.centraldogma.server.internal.api.WatchService;
import com.linecorp.centraldogma.server.internal.api.auth.ApplicationTokenAuthorizer;
import com.linecorp.centraldogma.server.internal.api.converter.HttpApiRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.HttpApiResponseConverter;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.command.ProjectInitializingCommandExecutor;
import com.linecorp.centraldogma.server.internal.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.metadata.MetadataService;
import com.linecorp.centraldogma.server.internal.metadata.MetadataServiceInjector;
import com.linecorp.centraldogma.server.internal.metadata.MigrationUtil;
import com.linecorp.centraldogma.server.internal.mirror.DefaultMirroringService;
import com.linecorp.centraldogma.server.internal.replication.ReplicationException;
import com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.SafeProjectManager;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaExceptionTranslator;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaServiceImpl;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaTimeoutScheduler;
import com.linecorp.centraldogma.server.internal.thrift.TokenlessClientLogger;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * Central Dogma server.
 *
 * @see CentralDogmaBuilder
 */
public class CentralDogma {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogma.class);

    static {
        Jackson.registerModules(new SimpleModule().addSerializer(CacheStats.class, new CacheStatsSerializer()));
    }

    /**
     * Creates a new instance from the given configuration file and security config.
     *
     * @throws IOException if failed to load the configuration from the specified file
     */
    public static CentralDogma forConfig(File configFile, @Nullable Ini securityConfig) throws IOException {
        requireNonNull(configFile, "configFile");
        return new CentralDogma(Jackson.readValue(configFile, CentralDogmaConfig.class),
                                securityConfig);
    }

    private final CentralDogmaConfig cfg;

    @Nullable
    private final Ini securityConfig;

    private volatile ProjectManager pm;
    private volatile Server server;
    private ExecutorService repositoryWorker;
    private CommandExecutor executor;
    private DefaultMirroringService mirroringService;

    CentralDogma(CentralDogmaConfig cfg, @Nullable Ini securityConfig) {
        this.cfg = requireNonNull(cfg, "cfg");

        if (cfg.isSecurityEnabled()) {
            requireNonNull(securityConfig, "securityConfig (must be non-null if securityEnabled is true)");
            final Ini iniCopy = new Ini();
            iniCopy.putAll(securityConfig);
            this.securityConfig = iniCopy;
        } else {
            this.securityConfig = null;
        }
    }

    /**
     * Returns the primary port of the server.
     *
     * @return the primary {@link ServerPort} if the server is started. {@link Optional#empty()} otherwise.
     */
    public Optional<ServerPort> activePort() {
        final Server server = this.server;
        return server != null ? server.activePort() : Optional.empty();
    }

    /**
     * Returns the ports of the server.
     *
     * @return the {@link Map} which contains the pairs of local {@link InetSocketAddress} and
     *         {@link ServerPort} is the server is started. {@link Optional#empty()} otherwise.
     */
    public Map<InetSocketAddress, ServerPort> activePorts() {
        final Server server = this.server;
        if (server != null) {
            return server.activePorts();
        } else {
            return Collections.emptyMap();
        }
    }

    /**
     * Returns the {@link MirroringService} of the server.
     *
     * @return the {@link MirroringService} if the server is started and mirroring is enabled.
     *         {@link Optional#empty()} otherwise.
     */
    public Optional<MirroringService> mirroringService() {
        return Optional.ofNullable(mirroringService);
    }

    /**
     * Returns the cache stats of the server.
     */
    public Optional<CacheStats> cacheStats() {
        // FIXME(trustin): Remove this from the public API.
        final ProjectManager pm = this.pm;
        if (pm == null) {
            return Optional.empty();
        }

        return Optional.of(pm.cacheStats());
    }

    /**
     * Starts the server. This method does nothing if the server is started already.
     */
    public synchronized void start() {
        boolean success = false;
        ThreadPoolExecutor repositoryWorker = null;
        ProjectManager pm = null;
        DefaultMirroringService mirroringService = null;
        CommandExecutor executor = null;
        Server server = null;
        CentralDogmaSecurityManager securityManager = null;
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

            mirroringService = new DefaultMirroringService(new File(cfg.dataDir(), "_mirrors"),
                                                           pm,
                                                           cfg.numMirroringThreads(),
                                                           cfg.maxNumFilesPerMirror(),
                                                           cfg.maxNumBytesPerMirror());

            if (cfg.isSecurityEnabled()) {
                securityManager = new CentralDogmaSecurityManager(cfg.dataDir(), securityConfig,
                                                                  cfg.webAppSessionTimeoutMillis());
            }

            logger.info("Starting the command executor ..");
            executor = startCommandExecutor(pm, mirroringService, repositoryWorker, securityManager);
            logger.info("Started the command executor");

            initializeInternalProject(executor);

            // Migrate tokens and create metadata files if it does not exist.
            MigrationUtil.migrate(pm, executor);

            logger.info("Starting the RPC server");
            server = startServer(pm, executor, securityManager);
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
            ProjectManager pm, DefaultMirroringService mirroringService,
            Executor repositoryWorker, @Nullable CentralDogmaSecurityManager securityManager) {

        final CommandExecutor executor;
        final ReplicationMethod replicationMethod = cfg.replicationConfig().method();
        switch (replicationMethod) {
            case ZOOKEEPER:
                executor = newZooKeeperCommandExecutor(pm, repositoryWorker, securityManager);
                break;
            case NONE:
                logger.info("No replication mechanism specified; entering standalone");
                executor = new StandaloneCommandExecutor(pm, securityManager, repositoryWorker);
                break;
            default:
                throw new Error("unknown replication method: " + replicationMethod);
        }

        final CommandExecutor projInitExecutor = new ProjectInitializingCommandExecutor(executor);
        try {
            projInitExecutor.start(() -> {
                if (cfg.isMirroringEnabled()) {
                    logger.info("Starting the mirroring service ..");
                    mirroringService.start(projInitExecutor);
                    logger.info("Started the mirroring service");
                } else {
                    logger.info("Not starting the mirroring service because it's disabled.");
                }

                if (securityManager != null) {
                    logger.info("Starting the periodic session validation ..");
                    securityManager.enableSessionValidation();
                    logger.info("Started the periodic session validation");
                }
            }, () -> {
                if (cfg.isMirroringEnabled()) {
                    logger.info("Stopping the mirroring service ..");
                    mirroringService.stop();
                    logger.info("Stopped the mirroring service");
                }

                if (securityManager != null) {
                    logger.info("Stopping the periodic session validation ..");
                    securityManager.disableSessionValidation();
                    logger.info("Stopped the periodic session validation");
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to start the command executor. Entering read-only.", e);
        }

        return projInitExecutor;
    }

    private Server startServer(ProjectManager pm, CommandExecutor executor,
                               @Nullable CentralDogmaSecurityManager securityManager) {
        final ServerBuilder sb = new ServerBuilder();

        boolean requiresTls = false;
        for (final ServerPort p : cfg.ports()) {
            sb.port(p);
            if (p.protocol().isTls()) {
                requiresTls = true;
            }
        }
        if (requiresTls) {
            try {
                final TlsConfig tlsConfig = cfg.tls();
                if (tlsConfig != null) {
                    sb.tls(tlsConfig.keyCertChainFile(), tlsConfig.keyFile(), tlsConfig.keyPassword());
                } else {
                    // TODO(hyangtack) Replace sb.tls() with sb.tlsSelfSigned() later.
                    // https://github.com/line/armeria/pull/1085
                    logger.warn(
                            "Missing TLS configuration. Generating a self-signed certificate for TLS support.");
                    final SelfSignedCertificate ssc = new SelfSignedCertificate();
                    sb.tls(ssc.certificate(), ssc.privateKey());
                }
            } catch (Exception e) {
                Exceptions.throwUnsafely(e);
            }
        }

        cfg.numWorkers().ifPresent(
                numWorkers -> sb.workerGroup(EventLoopGroups.newEventLoopGroup(numWorkers), true));
        cfg.maxNumConnections().ifPresent(sb::maxNumConnections);
        cfg.idleTimeoutMillis().ifPresent(sb::idleTimeoutMillis);
        cfg.requestTimeoutMillis().ifPresent(sb::defaultRequestTimeoutMillis);
        cfg.maxFrameLength().ifPresent(sb::defaultMaxRequestLength);
        cfg.gracefulShutdownTimeout().ifPresent(
                t -> sb.gracefulShutdownTimeout(t.quietPeriodMillis(), t.timeoutMillis()));

        final WatchService watchService = new WatchService();
        sb.serverListener(new ServerListenerAdapter() {
            @Override
            public void serverStopping(Server server) {
                watchService.serverStopping();
            }
        });

        configureThriftService(sb, pm, executor, watchService);

        sb.service("/hostname", HttpFileService.forVfs(new AbstractHttpVfs() {
            @Override
            public Entry get(String path, @Nullable String contentEncoding) {
                requireNonNull(path, "path");
                return new ByteArrayEntry(path, MediaType.PLAIN_TEXT_UTF_8,
                                          server.defaultHostname().getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public String meterTag() {
                return "hostname";
            }
        }));

        sb.service("/cache_stats", new AbstractHttpService() {
            @Override
            protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                    throws Exception {
                return HttpResponse.of(HttpStatus.OK,
                                       MediaType.JSON_UTF_8,
                                       Jackson.writeValueAsPrettyString(pm.cacheStats()));
            }
        });

        sb.service("/monitor/l7check", new HttpHealthCheckService());

        // TODO(hyangtack): This service is temporarily added to support redirection from '/docs' to '/docs/'.
        //                  It would be removed if this kind of redirection is handled by Armeria.
        sb.service("/docs", new AbstractHttpService() {
            @Override
            protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                    throws Exception {
                return HttpResponse.of(HttpHeaders.of(HttpStatus.TEMPORARY_REDIRECT)
                                                  .set(HttpHeaderNames.LOCATION, "/docs/"));
            }
        });
        sb.serviceUnder("/docs/",
                        new DocServiceBuilder().exampleHttpHeaders(
                                CentralDogmaService.class,
                                HttpHeaders.of(HttpHeaderNames.AUTHORIZATION,
                                               "bearer " + CsrfToken.ANONYMOUS))
                                               .build());

        configureHttpApi(sb, pm, executor, watchService, securityManager);

        final String accessLogFormat = cfg.accessLogFormat();
        if (isNullOrEmpty(accessLogFormat)) {
            sb.accessLogWriter(AccessLogWriters.disabled());
        } else if ("common".equals(accessLogFormat)) {
            sb.accessLogWriter(AccessLogWriters.common());
        } else if ("combined".equals(accessLogFormat)) {
            sb.accessLogWriter(AccessLogWriters.combined());
        } else {
            sb.accessLogFormat(accessLogFormat);
        }

        final Server s = sb.build();
        s.start().join();
        return s;
    }

    private CommandExecutor newZooKeeperCommandExecutor(ProjectManager pm, Executor repositoryWorker,
                                                        @Nullable CentralDogmaSecurityManager securityManager) {

        final ZooKeeperReplicationConfig zkCfg = (ZooKeeperReplicationConfig) cfg.replicationConfig();

        // Read or generate the replica ID.
        final File replicaIdFile =
                new File(cfg.dataDir().getAbsolutePath() + File.separatorChar + "replica_id");
        final String replicaId;

        if (replicaIdFile.exists()) {
            // Read the replica ID.
            try {
                final List<String> lines = Files.readAllLines(replicaIdFile.toPath());
                if (lines.isEmpty()) {
                    throw new IllegalStateException("replica_id contains no lines.");
                }
                replicaId = lines.get(0).trim();
                if (replicaId.isEmpty()) {
                    throw new IllegalStateException("replica_id is empty.");
                }
            } catch (Exception e) {
                throw new ReplicationException("failed to retrieve the replica ID from: " + replicaIdFile, e);
            }
            logger.info("Using ZooKeeper-based replication mechanism with an existing replica ID: {}",
                        replicaId);
        } else {
            // Generate a replica ID.
            replicaId = UUID.randomUUID().toString();
            try {
                Files.write(replicaIdFile.toPath(), ImmutableList.of(replicaId));
            } catch (Exception e) {
                throw new ReplicationException("failed to generate a replica ID into: " + replicaIdFile, e);
            }
            logger.info("Using ZooKeeper-based replication mechanism with a generated replica ID: {}",
                        replicaId);
        }

        // TODO(trustin): Provide a way to restart/reload the replicator
        //                so that we can recover from ZooKeeper maintenance automatically.
        final File revisionFile =
                new File(cfg.dataDir().getAbsolutePath() + File.separatorChar + "last_revision");

        return ZooKeeperCommandExecutor.builder()
                                       .replicaId(replicaId)
                                       .delegate(new StandaloneCommandExecutor(replicaId, pm,
                                                                               securityManager,
                                                                               repositoryWorker))
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

    private void configureThriftService(ServerBuilder sb, ProjectManager pm, CommandExecutor executor,
                                        WatchService watchService) {
        final CentralDogmaServiceImpl service =
                new CentralDogmaServiceImpl(pm, executor, watchService);

        Service<HttpRequest, HttpResponse> thriftService =
                ThriftCallService.of(service)
                                 .decorate(CentralDogmaTimeoutScheduler::new)
                                 .decorate(CentralDogmaExceptionTranslator::new)
                                 .decorate(THttpService.newDecorator());

        if (cfg.isCsrfTokenRequiredForThrift()) {
            thriftService = thriftService.decorate(HttpAuthService.newDecorator(new CsrfTokenAuthorizer()));
        } else {
            thriftService = thriftService.decorate(TokenlessClientLogger::new);
        }

        sb.service("/cd/thrift/v1", thriftService);
    }

    private void configureHttpApi(ServerBuilder sb,
                                  ProjectManager pm, CommandExecutor executor, WatchService watchService,
                                  @Nullable CentralDogmaSecurityManager securityManager) {
        // TODO(hyangtack) Replace the prefix with something like "/api/web/" or "/api/admin/".
        final String apiV0PathPrefix = "/api/v0/";

        final MetadataService mds = new MetadataService(pm, executor);

        final Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator;
        if (cfg.isSecurityEnabled()) {
            requireNonNull(securityManager, "securityManager");
            sb.service(apiV0PathPrefix + "authenticate", new LoginService(securityManager, executor))
              .service(apiV0PathPrefix + "logout", new LogoutService(securityManager, executor));

            sb.service("/security_enabled", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });

            final ApplicationTokenAuthorizer ata =
                    new ApplicationTokenAuthorizer(mds::findTokenBySecret);
            final SessionTokenAuthorizer sta = new SessionTokenAuthorizer(securityManager,
                                                                          cfg.administrators());

            decorator = MetadataServiceInjector.newDecorator(mds)
                                               .andThen(HttpAuthService.newDecorator(ata, sta));
        } else {
            decorator = MetadataServiceInjector.newDecorator(mds)
                                               .andThen(HttpAuthService.newDecorator(
                                                       new CsrfTokenAuthorizer()));
        }

        final SafeProjectManager safePm = new SafeProjectManager(pm);

        final HttpApiRequestConverter v1RequestConverter = new HttpApiRequestConverter(safePm);
        final HttpApiResponseConverter v1ResponseConverter = new HttpApiResponseConverter();

        sb.annotatedService(API_V1_PATH_PREFIX,
                            new AdministrativeService(safePm, executor), decorator,
                            v1RequestConverter, v1ResponseConverter);
        sb.annotatedService(API_V1_PATH_PREFIX,
                            new ProjectServiceV1(safePm, executor, mds), decorator,
                            v1RequestConverter, v1ResponseConverter);
        sb.annotatedService(API_V1_PATH_PREFIX,
                            new RepositoryServiceV1(safePm, executor, mds), decorator,
                            v1RequestConverter, v1ResponseConverter);
        sb.annotatedService(API_V1_PATH_PREFIX,
                            new ContentServiceV1(safePm, executor, watchService), decorator,
                            v1RequestConverter, v1ResponseConverter);

        if (cfg.isSecurityEnabled()) {
            sb.annotatedService(API_V1_PATH_PREFIX,
                                new MetadataApiService(mds), decorator,
                                v1RequestConverter, v1ResponseConverter);
            sb.annotatedService(API_V1_PATH_PREFIX, new TokenService(pm, executor, mds),
                                decorator, v1RequestConverter, v1ResponseConverter);
        }

        if (cfg.isWebAppEnabled()) {
            final RestfulJsonResponseConverter httpApiV0Converter = new RestfulJsonResponseConverter();

            // TODO(hyangtack): Simplify this if https://github.com/line/armeria/issues/582 is resolved.
            sb.annotatedService(apiV0PathPrefix, new UserService(safePm, executor),
                                decorator, httpApiV0Converter)
              .annotatedService(apiV0PathPrefix, new RepositoryService(safePm, executor),
                                decorator, httpApiV0Converter);
        }

        sb.serviceUnder("/", HttpFileService.forClassPath("webapp"));
    }

    /**
     * Stops the server. This method does nothing if the server is stopped already.
     */
    public synchronized void stop() {
        if (server == null) {
            return;
        }

        final Server server = this.server;
        final CommandExecutor executor = this.executor;
        final DefaultMirroringService mirroringService = this.mirroringService;
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
            Server server, CommandExecutor executor, DefaultMirroringService mirroringService,
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
