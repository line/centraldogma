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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V0_PATH_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.HEALTH_CHECK_PATH;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.METRICS_PATH;
import static com.linecorp.centraldogma.server.auth.AuthProvider.BUILTIN_WEB_BASE_PATH;
import static com.linecorp.centraldogma.server.auth.AuthProvider.LOGIN_API_ROUTES;
import static com.linecorp.centraldogma.server.auth.AuthProvider.LOGIN_PATH;
import static com.linecorp.centraldogma.server.auth.AuthProvider.LOGOUT_API_ROUTES;
import static com.linecorp.centraldogma.server.auth.AuthProvider.LOGOUT_PATH;
import static com.linecorp.centraldogma.server.internal.storage.project.ProjectInitializer.initializeInternalProject;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.StartStopSupport;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServiceNaming;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.server.file.FileService;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.healthcheck.SettableHealthChecker;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftCallService;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;
import com.linecorp.centraldogma.server.auth.AuthConfig;
import com.linecorp.centraldogma.server.auth.AuthProvider;
import com.linecorp.centraldogma.server.auth.AuthProviderParameters;
import com.linecorp.centraldogma.server.auth.SessionManager;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.admin.auth.CachedSessionManager;
import com.linecorp.centraldogma.server.internal.admin.auth.CsrfTokenAuthorizer;
import com.linecorp.centraldogma.server.internal.admin.auth.ExpiredSessionDeletingSessionManager;
import com.linecorp.centraldogma.server.internal.admin.auth.FileBasedSessionManager;
import com.linecorp.centraldogma.server.internal.admin.auth.OrElseDefaultHttpFileService;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionTokenAuthorizer;
import com.linecorp.centraldogma.server.internal.admin.service.DefaultLogoutService;
import com.linecorp.centraldogma.server.internal.admin.service.RepositoryService;
import com.linecorp.centraldogma.server.internal.admin.service.UserService;
import com.linecorp.centraldogma.server.internal.admin.util.RestfulJsonResponseConverter;
import com.linecorp.centraldogma.server.internal.api.AdministrativeService;
import com.linecorp.centraldogma.server.internal.api.ContentServiceV1;
import com.linecorp.centraldogma.server.internal.api.CredentialServiceV1;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService;
import com.linecorp.centraldogma.server.internal.api.MirroringServiceV1;
import com.linecorp.centraldogma.server.internal.api.ProjectServiceV1;
import com.linecorp.centraldogma.server.internal.api.RepositoryServiceV1;
import com.linecorp.centraldogma.server.internal.api.TokenService;
import com.linecorp.centraldogma.server.internal.api.WatchService;
import com.linecorp.centraldogma.server.internal.api.auth.ApplicationTokenAuthorizer;
import com.linecorp.centraldogma.server.internal.api.converter.HttpApiRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.HttpApiResponseConverter;
import com.linecorp.centraldogma.server.internal.mirror.DefaultMirroringServicePlugin;
import com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.SafeProjectManager;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaExceptionTranslator;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaServiceImpl;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaTimeoutScheduler;
import com.linecorp.centraldogma.server.internal.thrift.TokenlessClientLogger;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.MetadataServiceInjector;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginTarget;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.DiskSpaceMetrics;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;

/**
 * Central Dogma server.
 *
 * @see CentralDogmaBuilder
 */
public class CentralDogma implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogma.class);

    static {
        Jackson.registerModules(new SimpleModule().addSerializer(CacheStats.class, new CacheStatsSerializer()));
    }

    /**
     * Creates a new instance from the given configuration file.
     *
     * @throws IOException if failed to load the configuration from the specified file
     */
    public static CentralDogma forConfig(File configFile) throws IOException {
        requireNonNull(configFile, "configFile");
        return new CentralDogma(Jackson.readValue(configFile, CentralDogmaConfig.class),
                                Flags.meterRegistry());
    }

    private final SettableHealthChecker serverHealth = new SettableHealthChecker(false);
    private final CentralDogmaStartStop startStop;

    private final AtomicInteger numPendingStopRequests = new AtomicInteger();

    @Nullable
    private final PluginGroup pluginsForAllReplicas;
    @Nullable
    private final PluginGroup pluginsForLeaderOnly;

    private final CentralDogmaConfig cfg;
    @Nullable
    private volatile ProjectManager pm;
    @Nullable
    private volatile Server server;
    @Nullable
    private ExecutorService repositoryWorker;
    @Nullable
    private ScheduledExecutorService purgeWorker;
    @Nullable
    private CommandExecutor executor;
    private final MeterRegistry meterRegistry;
    @Nullable
    MeterRegistry meterRegistryToBeClosed;
    @Nullable
    private SessionManager sessionManager;

    CentralDogma(CentralDogmaConfig cfg, MeterRegistry meterRegistry) {
        this.cfg = requireNonNull(cfg, "cfg");
        pluginsForAllReplicas = PluginGroup.loadPlugins(
                CentralDogma.class.getClassLoader(), PluginTarget.ALL_REPLICAS, cfg);
        pluginsForLeaderOnly = PluginGroup.loadPlugins(
                CentralDogma.class.getClassLoader(), PluginTarget.LEADER_ONLY, cfg);
        startStop = new CentralDogmaStartStop(pluginsForAllReplicas);
        this.meterRegistry = meterRegistry;
    }

    /**
     * Returns the configuration of the server.
     *
     * @return the {@link CentralDogmaConfig} instance which is used for configuring this {@link CentralDogma}.
     */
    public CentralDogmaConfig config() {
        return cfg;
    }

    /**
     * Returns the primary port of the server.
     *
     * @return the primary {@link ServerPort} if the server is started. {@link Optional#empty()} otherwise.
     */
    @Nullable
    public ServerPort activePort() {
        final Server server = this.server;
        return server != null ? server.activePort() : null;
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
        if (pluginsForLeaderOnly == null) {
            return Optional.empty();
        }
        return pluginsForLeaderOnly.findFirstPlugin(DefaultMirroringServicePlugin.class)
                                   .map(DefaultMirroringServicePlugin::mirroringService);
    }

    /**
     * Returns the {@link Plugin}s which have been loaded.
     *
     * @param target the {@link PluginTarget} of the {@link Plugin}s to be returned
     */
    public List<Plugin> plugins(PluginTarget target) {
        switch (requireNonNull(target, "target")) {
            case LEADER_ONLY:
                return pluginsForLeaderOnly != null ? ImmutableList.copyOf(pluginsForLeaderOnly.plugins())
                                                    : ImmutableList.of();
            case ALL_REPLICAS:
                return pluginsForAllReplicas != null ? ImmutableList.copyOf(pluginsForAllReplicas.plugins())
                                                     : ImmutableList.of();
            default:
                // Should not reach here.
                throw new Error("Unknown plugin target: " + target);
        }
    }

    /**
     * Returns the {@link MeterRegistry} that contains the stats related with the server.
     */
    public Optional<MeterRegistry> meterRegistry() {
        return Optional.ofNullable(meterRegistry);
    }

    /**
     * Starts the server.
     */
    public CompletableFuture<Void> start() {
        return startStop.start(true);
    }

    /**
     * Stops the server. This method does nothing if the server is stopped already.
     */
    public CompletableFuture<Void> stop() {
        serverHealth.setHealthy(false);
        numPendingStopRequests.incrementAndGet();
        return startStop.stop().thenRun(numPendingStopRequests::decrementAndGet);
    }

    @Override
    public void close() {
        startStop.close();
    }

    private void doStart() throws Exception {
        boolean success = false;
        ExecutorService repositoryWorker = null;
        ScheduledExecutorService purgeWorker = null;
        ProjectManager pm = null;
        CommandExecutor executor = null;
        Server server = null;
        SessionManager sessionManager = null;
        try {
            logger.info("Starting the Central Dogma ..");

            final ThreadPoolExecutor repositoryWorkerImpl = new ThreadPoolExecutor(
                    cfg.numRepositoryWorkers(), cfg.numRepositoryWorkers(),
                    60, TimeUnit.SECONDS, new LinkedTransferQueue<>(),
                    new DefaultThreadFactory("repository-worker", true));
            repositoryWorkerImpl.allowCoreThreadTimeOut(true);
            repositoryWorker = ExecutorServiceMetrics.monitor(meterRegistry, repositoryWorkerImpl,
                                                              "repositoryWorker");

            logger.info("Starting the project manager: {}", cfg.dataDir());

            purgeWorker = Executors.newSingleThreadScheduledExecutor(
                    new DefaultThreadFactory("purge-worker", true));

            pm = new DefaultProjectManager(cfg.dataDir(), repositoryWorker, purgeWorker,
                                           meterRegistry, cfg.repositoryCacheSpec());

            logger.info("Started the project manager: {}", pm);

            logger.info("Current settings:\n{}", cfg);

            sessionManager = initializeSessionManager();

            logger.info("Starting the command executor ..");
            executor = startCommandExecutor(pm, repositoryWorker, purgeWorker,
                                            meterRegistry, sessionManager);
            if (executor.isWritable()) {
                logger.info("Started the command executor.");

                initializeInternalProject(executor);
            }

            logger.info("Starting the RPC server.");
            server = startServer(pm, executor, meterRegistry, sessionManager);
            logger.info("Started the RPC server at: {}", server.activePorts());
            logger.info("Started the Central Dogma successfully.");
            success = true;
        } finally {
            if (success) {
                serverHealth.setHealthy(true);
                this.repositoryWorker = repositoryWorker;
                this.purgeWorker = purgeWorker;
                this.pm = pm;
                this.executor = executor;
                this.server = server;
                this.sessionManager = sessionManager;
            } else {
                doStop(server, executor, pm, repositoryWorker, purgeWorker, sessionManager);
            }
        }
    }

    private CommandExecutor startCommandExecutor(
            ProjectManager pm, Executor repositoryWorker,
            ScheduledExecutorService purgeWorker, MeterRegistry meterRegistry,
            @Nullable SessionManager sessionManager) {

        final Consumer<CommandExecutor> onTakeLeadership = exec -> {
            if (pluginsForLeaderOnly != null) {
                logger.info("Starting plugins on the leader replica ..");
                pluginsForLeaderOnly
                        .start(cfg, pm, exec, meterRegistry, purgeWorker).handle((unused, cause) -> {
                    if (cause == null) {
                        logger.info("Started plugins on the leader replica.");
                    } else {
                        logger.error("Failed to start plugins on the leader replica..", cause);
                    }
                    return null;
                });
            }
        };

        final Consumer<CommandExecutor> onReleaseLeadership = exec -> {
            if (pluginsForLeaderOnly != null) {
                logger.info("Stopping plugins on the leader replica ..");
                pluginsForLeaderOnly.stop(cfg, pm, exec, meterRegistry, purgeWorker).handle((unused, cause) -> {
                    if (cause == null) {
                        logger.info("Stopped plugins on the leader replica.");
                    } else {
                        logger.error("Failed to stop plugins on the leader replica.", cause);
                    }
                    return null;
                });
            }
        };

        final CommandExecutor executor;
        final ReplicationMethod replicationMethod = cfg.replicationConfig().method();
        switch (replicationMethod) {
            case ZOOKEEPER:
                executor = newZooKeeperCommandExecutor(pm, repositoryWorker, meterRegistry, sessionManager,
                                                       onTakeLeadership, onReleaseLeadership);
                break;
            case NONE:
                logger.info("No replication mechanism specified; entering standalone");
                executor = new StandaloneCommandExecutor(pm, repositoryWorker, sessionManager,
                                                         cfg.writeQuotaPerRepository(),
                                                         onTakeLeadership, onReleaseLeadership);
                break;
            default:
                throw new Error("unknown replication method: " + replicationMethod);
        }

        try {
            final CompletableFuture<Void> startFuture = executor.start();
            while (!startFuture.isDone()) {
                if (numPendingStopRequests.get() > 0) {
                    // Stop request has been issued.
                    executor.stop().get();
                    break;
                }

                try {
                    startFuture.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException unused) {
                    // Taking long time ..
                }
            }

            // Trigger the exception if any.
            startFuture.get();
        } catch (Exception e) {
            logger.warn("Failed to start the command executor. Entering read-only.", e);
        }

        return executor;
    }

    @Nullable
    private SessionManager initializeSessionManager() throws Exception {
        final AuthConfig authCfg = cfg.authConfig();
        if (authCfg == null) {
            return null;
        }

        boolean success = false;
        SessionManager manager = null;
        try {
            manager = new FileBasedSessionManager(new File(cfg.dataDir(), "_sessions").toPath(),
                                                  authCfg.sessionValidationSchedule());
            manager = new CachedSessionManager(manager, Caffeine.from(authCfg.sessionCacheSpec()).build());
            manager = new ExpiredSessionDeletingSessionManager(manager);
            success = true;
            return manager;
        } finally {
            if (!success && manager != null) {
                try {
                    // It will eventually close FileBasedSessionManager because the other managers just forward
                    // the close method call to their delegate.
                    manager.close();
                } catch (Exception e) {
                    logger.warn("Failed to close a session manager.", e);
                }
            }
        }
    }

    private Server startServer(ProjectManager pm, CommandExecutor executor,
                               MeterRegistry meterRegistry, @Nullable SessionManager sessionManager) {
        final ServerBuilder sb = Server.builder();
        sb.verboseResponses(true);
        cfg.ports().forEach(sb::port);

        if (cfg.ports().stream().anyMatch(ServerPort::hasTls)) {
            try {
                final TlsConfig tlsConfig = cfg.tls();
                if (tlsConfig != null) {
                    sb.tls(tlsConfig.keyCertChainFile(), tlsConfig.keyFile(), tlsConfig.keyPassword());
                } else {
                    logger.warn(
                            "Missing TLS configuration. Generating a self-signed certificate for TLS support.");
                    sb.tlsSelfSigned();
                }
            } catch (Exception e) {
                Exceptions.throwUnsafely(e);
            }
        }

        sb.clientAddressSources(cfg.clientAddressSourceList());
        sb.clientAddressTrustedProxyFilter(cfg.trustedProxyAddressPredicate());

        configCors(sb, config().corsConfig());

        cfg.numWorkers().ifPresent(
                numWorkers -> sb.workerGroup(EventLoopGroups.newEventLoopGroup(numWorkers), true));
        cfg.maxNumConnections().ifPresent(sb::maxNumConnections);
        cfg.idleTimeoutMillis().ifPresent(sb::idleTimeoutMillis);
        cfg.requestTimeoutMillis().ifPresent(sb::requestTimeoutMillis);
        cfg.maxFrameLength().ifPresent(sb::maxRequestLength);
        cfg.gracefulShutdownTimeout().ifPresent(
                t -> sb.gracefulShutdownTimeoutMillis(t.quietPeriodMillis(), t.timeoutMillis()));

        final MetadataService mds = new MetadataService(pm, executor);
        final WatchService watchService = new WatchService(meterRegistry);
        final AuthProvider authProvider = createAuthProvider(executor, sessionManager, mds);

        configureThriftService(sb, pm, executor, watchService, mds);

        sb.service("/title", webAppTitleFile(cfg.webAppTitle(), SystemInfo.hostname()).asService());

        sb.service(HEALTH_CHECK_PATH, HealthCheckService.builder()
                                                        .checkers(serverHealth)
                                                        .build());

        sb.serviceUnder("/docs/",
                        DocService.builder()
                                  .exampleHeaders(CentralDogmaService.class,
                                                  HttpHeaders.of(HttpHeaderNames.AUTHORIZATION,
                                                                 "Bearer " + CsrfToken.ANONYMOUS))
                                  .build());

        configureHttpApi(sb, pm, executor, watchService, mds, authProvider, sessionManager);

        configureMetrics(sb, meterRegistry);

        // Configure access log format.
        final String accessLogFormat = cfg.accessLogFormat();
        if (isNullOrEmpty(accessLogFormat)) {
            sb.accessLogWriter(AccessLogWriter.disabled(), true);
        } else if ("common".equals(accessLogFormat)) {
            sb.accessLogWriter(AccessLogWriter.common(), true);
        } else if ("combined".equals(accessLogFormat)) {
            sb.accessLogWriter(AccessLogWriter.combined(), true);
        } else {
            sb.accessLogFormat(accessLogFormat);
        }

        final Server s = sb.build();
        s.start().join();
        return s;
    }

    static HttpFile webAppTitleFile(@Nullable String webAppTitle, String hostname) {
        requireNonNull(hostname, "hostname");
        final Map<String, String> titleAndHostname = ImmutableMap.of(
                "title", firstNonNull(webAppTitle, "Central Dogma at {{hostname}}"),
                "hostname", hostname);

        try {
            final HttpData data = HttpData.ofUtf8(Jackson.writeValueAsString(titleAndHostname));
            return HttpFile.builder(data)
                           .contentType(MediaType.JSON_UTF_8)
                           .cacheControl(ServerCacheControl.REVALIDATED)
                           .build();
        } catch (JsonProcessingException e) {
            throw new Error("Failed to encode the title and hostname:", e);
        }
    }

    @Nullable
    private AuthProvider createAuthProvider(
            CommandExecutor commandExecutor, @Nullable SessionManager sessionManager, MetadataService mds) {
        final AuthConfig authCfg = cfg.authConfig();
        if (authCfg == null) {
            return null;
        }

        checkState(sessionManager != null, "SessionManager is null");
        final AuthProviderParameters parameters = new AuthProviderParameters(
                // Find application first, then find the session token.
                new ApplicationTokenAuthorizer(mds::findTokenBySecret).orElse(
                        new SessionTokenAuthorizer(sessionManager, authCfg.administrators())),
                cfg,
                sessionManager::generateSessionId,
                // Propagate login and logout events to the other replicas.
                session -> commandExecutor.execute(Command.createSession(session)),
                sessionId -> commandExecutor.execute(Command.removeSession(sessionId)));
        return authCfg.factory().create(parameters);
    }

    private CommandExecutor newZooKeeperCommandExecutor(
            ProjectManager pm, Executor repositoryWorker, MeterRegistry meterRegistry,
            @Nullable SessionManager sessionManager,
            @Nullable Consumer<CommandExecutor> onTakeLeadership,
            @Nullable Consumer<CommandExecutor> onReleaseLeadership) {
        final ZooKeeperReplicationConfig zkCfg = (ZooKeeperReplicationConfig) cfg.replicationConfig();

        // Delete the old UUID replica ID which is not used anymore.
        new File(cfg.dataDir(), "replica_id").delete();

        // TODO(trustin): Provide a way to restart/reload the replicator
        //                so that we can recover from ZooKeeper maintenance automatically.
        return new ZooKeeperCommandExecutor(
                zkCfg, cfg.dataDir(),
                new StandaloneCommandExecutor(pm, repositoryWorker, sessionManager,
                        /* onTakeLeadership */ null, /* onReleaseLeadership */ null),
                meterRegistry, pm, config().writeQuotaPerRepository(), onTakeLeadership, onReleaseLeadership);
    }

    private void configureThriftService(ServerBuilder sb, ProjectManager pm, CommandExecutor executor,
                                        WatchService watchService, MetadataService mds) {
        final CentralDogmaServiceImpl service =
                new CentralDogmaServiceImpl(pm, executor, watchService, mds);

        HttpService thriftService =
                ThriftCallService.of(service)
                                 .decorate(CentralDogmaTimeoutScheduler::new)
                                 .decorate(CentralDogmaExceptionTranslator::new)
                                 .decorate(THttpService.newDecorator());

        if (cfg.isCsrfTokenRequiredForThrift()) {
            thriftService = thriftService.decorate(AuthService.newDecorator(new CsrfTokenAuthorizer()));
        } else {
            thriftService = thriftService.decorate(TokenlessClientLogger::new);
        }

        // Enable content compression for API responses.
        thriftService = thriftService.decorate(contentEncodingDecorator());

        sb.service("/cd/thrift/v1", thriftService);
    }

    private void configureHttpApi(ServerBuilder sb,
                                  ProjectManager pm, CommandExecutor executor,
                                  WatchService watchService, MetadataService mds,
                                  @Nullable AuthProvider authProvider,
                                  @Nullable SessionManager sessionManager) {
        Function<? super HttpService, ? extends HttpService> decorator;

        if (authProvider != null) {
            sb.service("/security_enabled", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });

            final AuthConfig authCfg = cfg.authConfig();
            assert authCfg != null : "authCfg";
            assert sessionManager != null : "sessionManager";
            final Authorizer<HttpRequest> tokenAuthorizer =
                    new ApplicationTokenAuthorizer(mds::findTokenBySecret)
                            .orElse(new SessionTokenAuthorizer(sessionManager,
                                                               authCfg.administrators()));
            decorator = MetadataServiceInjector
                    .newDecorator(mds)
                    .andThen(AuthService.builder()
                                        .add(tokenAuthorizer)
                                        .onFailure(new CentralDogmaAuthFailureHandler())
                                        .newDecorator());
        } else {
            decorator = MetadataServiceInjector
                    .newDecorator(mds)
                    .andThen(AuthService.newDecorator(new CsrfTokenAuthorizer()));
        }

        final SafeProjectManager safePm = new SafeProjectManager(pm);

        final HttpApiRequestConverter v1RequestConverter = new HttpApiRequestConverter(safePm);
        final HttpApiResponseConverter v1ResponseConverter = new HttpApiResponseConverter();

        // Enable content compression for API responses.
        decorator = decorator.andThen(contentEncodingDecorator());

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
                            new MirroringServiceV1(safePm, executor, mds), decorator,
                            v1RequestConverter, v1RequestConverter);
        sb.annotatedService(API_V1_PATH_PREFIX,
                            new CredentialServiceV1(safePm, executor, mds), decorator,
                            v1RequestConverter, v1RequestConverter);
        sb.annotatedService()
          .pathPrefix(API_V1_PATH_PREFIX)
          .defaultServiceNaming(new ServiceNaming() {
              private final String serviceName = ContentServiceV1.class.getName();
              private final String watchServiceName =
                      serviceName.replace("ContentServiceV1", "WatchContentServiceV1");

              @Override
              public String serviceName(ServiceRequestContext ctx) {
                  if (ctx.request().headers().contains(HttpHeaderNames.IF_NONE_MATCH)) {
                      return watchServiceName;
                  }
                  return serviceName;
              }
          })
          .decorator(decorator)
          .requestConverters(v1RequestConverter)
          .responseConverters(v1ResponseConverter)
          .build(new ContentServiceV1(safePm, executor, watchService));

        if (authProvider != null) {
            final AuthConfig authCfg = cfg.authConfig();
            assert authCfg != null : "authCfg";
            sb.annotatedService(API_V1_PATH_PREFIX,
                                new MetadataApiService(mds, authCfg.loginNameNormalizer()),
                                decorator, v1RequestConverter, v1ResponseConverter);
            sb.annotatedService(API_V1_PATH_PREFIX, new TokenService(pm, executor, mds),
                                decorator, v1RequestConverter, v1ResponseConverter);

            // authentication services:
            Optional.ofNullable(authProvider.loginApiService())
                    .ifPresent(login -> LOGIN_API_ROUTES.forEach(mapping -> sb.service(mapping, login)));

            // Provide logout API by default.
            final HttpService logout =
                    Optional.ofNullable(authProvider.logoutApiService())
                            .orElseGet(() -> new DefaultLogoutService(executor));
            for (Route route : LOGOUT_API_ROUTES) {
                sb.service(route, decorator.apply(logout));
            }

            authProvider.moreServices().forEach(sb::service);
        }

        if (cfg.isWebAppEnabled()) {
            final RestfulJsonResponseConverter httpApiV0Converter = new RestfulJsonResponseConverter();

            // TODO(hyangtack): Simplify this if https://github.com/line/armeria/issues/582 is resolved.
            sb.annotatedService(API_V0_PATH_PREFIX, new UserService(safePm, executor),
                                decorator, httpApiV0Converter)
              .annotatedService(API_V0_PATH_PREFIX, new RepositoryService(safePm, executor),
                                decorator, httpApiV0Converter);

            if (authProvider != null) {
                // Will redirect to /web/auth/login by default.
                sb.service(LOGIN_PATH, authProvider.webLoginService());
                // Will redirect to /web/auth/logout by default.
                sb.service(LOGOUT_PATH, authProvider.webLogoutService());

                sb.serviceUnder(BUILTIN_WEB_BASE_PATH, new OrElseDefaultHttpFileService(
                        FileService.builder(CentralDogma.class.getClassLoader(), "auth-webapp")
                                   .cacheControl(ServerCacheControl.REVALIDATED)
                                   .build(),
                        "/index.html"));
            }
            sb.serviceUnder("/",
                            FileService.builder(CentralDogma.class.getClassLoader(), "webapp")
                                       .cacheControl(ServerCacheControl.REVALIDATED)
                                       .build());
        }
    }

    private static void configCors(ServerBuilder sb, @Nullable CorsConfig corsConfig) {
        if (corsConfig == null) {
            return;
        }

        sb.decorator(CorsService.builder(corsConfig.allowedOrigins())
                                .allowRequestMethods(HttpMethod.knownMethods())
                                .allowAllRequestHeaders(true)
                                .allowCredentials()
                                .maxAge(corsConfig.maxAgeSeconds())
                                .newDecorator());
    }

    private static Function<? super HttpService, EncodingService> contentEncodingDecorator() {
        return delegate -> EncodingService
                .builder()
                .encodableContentTypes(contentType -> {
                    if ("application".equals(contentType.type())) {
                        final String subtype = contentType.subtype();
                        switch (subtype) {
                            case "json":
                            case "xml":
                            case "x-thrift":
                                return true;
                            default:
                                return subtype.endsWith("+json") ||
                                       subtype.endsWith("+xml") ||
                                       subtype.startsWith("vnd.apache.thrift.");
                        }
                    }
                    return false;
                })
                .build(delegate);
    }

    private void configureMetrics(ServerBuilder sb, MeterRegistry registry) {
        sb.meterRegistry(registry);

        // expose the prometheus endpoint if the registry is either a PrometheusMeterRegistry or
        // CompositeMeterRegistry
        if (registry instanceof PrometheusMeterRegistry) {
            final PrometheusMeterRegistry prometheusMeterRegistry = (PrometheusMeterRegistry) registry;
            sb.service(METRICS_PATH,
                       PrometheusExpositionService.of(prometheusMeterRegistry.getPrometheusRegistry()));
        } else if (registry instanceof CompositeMeterRegistry) {
            final PrometheusMeterRegistry prometheusMeterRegistry = PrometheusMeterRegistries.newRegistry();
            ((CompositeMeterRegistry) registry).add(prometheusMeterRegistry);
            sb.service(METRICS_PATH,
                       PrometheusExpositionService.of(prometheusMeterRegistry.getPrometheusRegistry()));
            meterRegistryToBeClosed = prometheusMeterRegistry;
        } else {
            logger.info("Not exposing a prometheus endpoint for the type: {}", registry.getClass());
        }

        sb.decorator(MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("api")));

        // Bind system metrics.
        new FileDescriptorMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);
        new DiskSpaceMetrics(cfg.dataDir()).bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);

        // Bind global thread pool metrics.
        ExecutorServiceMetrics.monitor(registry, ForkJoinPool.commonPool(), "commonPool");
    }

    private void doStop() {
        if (server == null) {
            return;
        }

        final Server server = this.server;
        final CommandExecutor executor = this.executor;
        final ProjectManager pm = this.pm;
        final ExecutorService repositoryWorker = this.repositoryWorker;
        final ExecutorService purgeWorker = this.purgeWorker;
        final SessionManager sessionManager = this.sessionManager;

        this.server = null;
        this.executor = null;
        this.pm = null;
        this.repositoryWorker = null;
        this.sessionManager = null;
        if (meterRegistryToBeClosed != null) {
            assert meterRegistry instanceof CompositeMeterRegistry;
            ((CompositeMeterRegistry) meterRegistry).remove(meterRegistryToBeClosed);
            meterRegistryToBeClosed.close();
            meterRegistryToBeClosed = null;
        }

        logger.info("Stopping the Central Dogma ..");
        if (!doStop(server, executor, pm, repositoryWorker, purgeWorker, sessionManager)) {
            logger.warn("Stopped the Central Dogma with failure.");
        } else {
            logger.info("Stopped the Central Dogma successfully.");
        }
    }

    private static boolean doStop(
            @Nullable Server server, @Nullable CommandExecutor executor,
            @Nullable ProjectManager pm,
            @Nullable ExecutorService repositoryWorker, @Nullable ExecutorService purgeWorker,
            @Nullable SessionManager sessionManager) {

        boolean success = true;
        try {
            if (sessionManager != null) {
                logger.info("Stopping the session manager ..");
                sessionManager.close();
                logger.info("Stopped the session manager.");
            }
        } catch (Throwable t) {
            success = false;
            logger.warn("Failed to stop the session manager:", t);
        }

        try {
            if (pm != null) {
                logger.info("Stopping the project manager ..");
                pm.close(ShuttingDownException::new);
                logger.info("Stopped the project manager.");
            }
        } catch (Throwable t) {
            success = false;
            logger.warn("Failed to stop the project manager:", t);
        }

        try {
            if (executor != null) {
                logger.info("Stopping the command executor ..");
                executor.stop();
                logger.info("Stopped the command executor.");
            }
        } catch (Throwable t) {
            success = false;
            logger.warn("Failed to stop the command executor:", t);
        }

        final BiFunction<ExecutorService, String, Boolean> stopWorker = (worker, name) -> {
            try {
                if (worker != null && !worker.isTerminated()) {
                    logger.info("Stopping the {} worker ..", name);
                    boolean interruptLater = false;
                    while (!worker.isTerminated()) {
                        worker.shutdownNow();
                        try {
                            worker.awaitTermination(1, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            // Interrupt later.
                            interruptLater = true;
                        }
                    }
                    logger.info("Stopped the {} worker.", name);

                    if (interruptLater) {
                        Thread.currentThread().interrupt();
                    }
                }
                return true;
            } catch (Throwable t) {
                logger.warn("Failed to stop the " + name + " worker:", t);
                return false;
            }
        };
        if (!stopWorker.apply(repositoryWorker, "repository")) {
            success = false;
        }
        if (!stopWorker.apply(purgeWorker, "purge")) {
            success = false;
        }

        try {
            if (server != null) {
                logger.info("Stopping the RPC server ..");
                server.stop().join();
                logger.info("Stopped the RPC server.");
            }
        } catch (Throwable t) {
            success = false;
            logger.warn("Failed to stop the RPC server:", t);
        }

        return success;
    }

    private final class CentralDogmaStartStop extends StartStopSupport<Void, Void, Void, Void> {

        @Nullable
        private final PluginGroup pluginsForAllReplicas;

        CentralDogmaStartStop(@Nullable PluginGroup pluginsForAllReplicas) {
            super(GlobalEventExecutor.INSTANCE);
            this.pluginsForAllReplicas = pluginsForAllReplicas;
        }

        @Override
        protected CompletionStage<Void> doStart(@Nullable Void unused) throws Exception {
            return execute("startup", () -> {
                try {
                    CentralDogma.this.doStart();
                    if (pluginsForAllReplicas != null) {
                        final ProjectManager pm = CentralDogma.this.pm;
                        final CommandExecutor executor = CentralDogma.this.executor;
                        final MeterRegistry meterRegistry = CentralDogma.this.meterRegistry;
                        if (pm != null && executor != null && meterRegistry != null) {
                            pluginsForAllReplicas.start(cfg, pm, executor, meterRegistry, purgeWorker).join();
                        }
                    }
                } catch (Exception e) {
                    Exceptions.throwUnsafely(e);
                }
            });
        }

        @Override
        protected CompletionStage<Void> doStop(@Nullable Void unused) throws Exception {
            return execute("shutdown", () -> {
                if (pluginsForAllReplicas != null) {
                    final ProjectManager pm = CentralDogma.this.pm;
                    final CommandExecutor executor = CentralDogma.this.executor;
                    final MeterRegistry meterRegistry = CentralDogma.this.meterRegistry;
                    if (pm != null && executor != null && meterRegistry != null) {
                        pluginsForAllReplicas.stop(cfg, pm, executor, meterRegistry, purgeWorker).join();
                    }
                }
                CentralDogma.this.doStop();
            });
        }

        private CompletionStage<Void> execute(String mode, Runnable task) {
            final CompletableFuture<Void> future = new CompletableFuture<>();
            final Thread thread = new Thread(() -> {
                try {
                    task.run();
                    future.complete(null);
                } catch (Throwable cause) {
                    future.completeExceptionally(cause);
                }
            }, "dogma-" + mode + "-0x" + Long.toHexString(CentralDogma.this.hashCode() & 0xFFFFFFFFL));
            thread.start();
            return future;
        }
    }
}
