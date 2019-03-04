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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V0_PATH_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.HEALTH_CHECK_PATH;
import static com.linecorp.centraldogma.server.auth.AuthProvider.BUILTIN_WEB_BASE_PATH;
import static com.linecorp.centraldogma.server.auth.AuthProvider.LOGIN_API_PATH_MAPPINGS;
import static com.linecorp.centraldogma.server.auth.AuthProvider.LOGIN_PATH;
import static com.linecorp.centraldogma.server.auth.AuthProvider.LOGOUT_API_PATH_MAPPINGS;
import static com.linecorp.centraldogma.server.auth.AuthProvider.LOGOUT_PATH;
import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.initializeInternalProject;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.collect.ImmutableMap.Builder;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.StartStopSupport;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.HttpAuthService;
import com.linecorp.armeria.server.auth.HttpAuthServiceBuilder;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.encoding.HttpEncodingService;
import com.linecorp.armeria.server.file.AbstractHttpVfs;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.server.file.HttpFileBuilder;
import com.linecorp.armeria.server.file.HttpFileService;
import com.linecorp.armeria.server.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftCallService;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;
import com.linecorp.centraldogma.server.auth.AuthConfig;
import com.linecorp.centraldogma.server.auth.AuthProvider;
import com.linecorp.centraldogma.server.auth.AuthProviderParameters;
import com.linecorp.centraldogma.server.internal.admin.auth.CachedSessionManager;
import com.linecorp.centraldogma.server.internal.admin.auth.CsrfTokenAuthorizer;
import com.linecorp.centraldogma.server.internal.admin.auth.ExpiredSessionDeletingSessionManager;
import com.linecorp.centraldogma.server.internal.admin.auth.FileBasedSessionManager;
import com.linecorp.centraldogma.server.internal.admin.auth.OrElseDefaultHttpFileService;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionManager;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionTokenAuthorizer;
import com.linecorp.centraldogma.server.internal.admin.service.DefaultLogoutService;
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
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.metadata.MetadataService;
import com.linecorp.centraldogma.server.internal.metadata.MetadataServiceInjector;
import com.linecorp.centraldogma.server.internal.metadata.MigrationUtil;
import com.linecorp.centraldogma.server.internal.mirror.DefaultMirroringService;
import com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.SafeProjectManager;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaExceptionTranslator;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaServiceImpl;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaTimeoutScheduler;
import com.linecorp.centraldogma.server.internal.thrift.TokenlessClientLogger;

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
        return new CentralDogma(Jackson.readValue(configFile, CentralDogmaConfig.class));
    }

    private final StartStopSupport<Void, Void> startStop = new CentralDogmaStartStop();
    private final AtomicInteger numPendingStopRequests = new AtomicInteger();

    private final CentralDogmaConfig cfg;
    @Nullable
    private volatile ProjectManager pm;
    @Nullable
    private volatile Server server;
    @Nullable
    private ExecutorService repositoryWorker;
    @Nullable
    private CommandExecutor executor;
    @Nullable
    private DefaultMirroringService mirroringService;
    @Nullable
    private SessionManager sessionManager;

    CentralDogma(CentralDogmaConfig cfg) {
        this.cfg = requireNonNull(cfg, "cfg");
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
     * Starts the server.
     */
    public CompletableFuture<Void> start() {
        return startStop.start(true);
    }

    /**
     * Stops the server. This method does nothing if the server is stopped already.
     */
    public CompletableFuture<Void> stop() {
        numPendingStopRequests.incrementAndGet();
        return startStop.stop().thenRun(numPendingStopRequests::decrementAndGet);
    }

    @Override
    public void close() {
        startStop.close();
    }

    private void doStart() throws Exception {
        boolean success = false;
        ThreadPoolExecutor repositoryWorker = null;
        ProjectManager pm = null;
        DefaultMirroringService mirroringService = null;
        CommandExecutor executor = null;
        Server server = null;
        SessionManager sessionManager = null;
        try {
            logger.info("Starting the Central Dogma ..");
            repositoryWorker = new ThreadPoolExecutor(
                    cfg.numRepositoryWorkers(), cfg.numRepositoryWorkers(),
                    60, TimeUnit.SECONDS, new LinkedTransferQueue<>(),
                    new DefaultThreadFactory("repository-worker", true));
            repositoryWorker.allowCoreThreadTimeOut(true);

            logger.info("Starting the project manager: {}", cfg.dataDir());

            pm = new DefaultProjectManager(cfg.dataDir(), repositoryWorker, cfg.repositoryCacheSpec());
            logger.info("Started the project manager: {}", pm);

            logger.info("Current settings:\n{}", cfg);

            mirroringService = new DefaultMirroringService(new File(cfg.dataDir(), "_mirrors"),
                                                           pm,
                                                           cfg.numMirroringThreads(),
                                                           cfg.maxNumFilesPerMirror(),
                                                           cfg.maxNumBytesPerMirror());
            sessionManager = initializeSessionManager();

            logger.info("Starting the command executor ..");

            executor = startCommandExecutor(pm, mirroringService, repositoryWorker, sessionManager);
            if (executor.isWritable()) {
                logger.info("Started the command executor.");

                initializeInternalProject(executor);

                // Migrate tokens and create metadata files if it does not exist.
                MigrationUtil.migrate(pm, executor);
            }

            logger.info("Starting the RPC server.");
            server = startServer(pm, executor, sessionManager);
            logger.info("Started the RPC server at: {}", server.activePorts());
            logger.info("Started the Central Dogma successfully.");
            success = true;
        } finally {
            if (success) {
                this.repositoryWorker = repositoryWorker;
                this.pm = pm;
                this.executor = executor;
                this.mirroringService = mirroringService;
                this.server = server;
                this.sessionManager = sessionManager;
            } else {
                doStop(server, executor, mirroringService, pm, repositoryWorker, sessionManager);
            }
        }
    }

    private CommandExecutor startCommandExecutor(
            ProjectManager pm, DefaultMirroringService mirroringService,
            Executor repositoryWorker, @Nullable SessionManager sessionManager) {

        final Consumer<CommandExecutor> onTakeLeadership = exec -> {
            if (cfg.isMirroringEnabled()) {
                logger.info("Starting the mirroring service ..");
                mirroringService.start(exec);
                logger.info("Started the mirroring service.");
            } else {
                logger.info("Not starting the mirroring service because it's disabled.");
            }
        };

        final Runnable onReleaseLeadership = () -> {
            if (cfg.isMirroringEnabled()) {
                logger.info("Stopping the mirroring service ..");
                mirroringService.stop();
                logger.info("Stopped the mirroring service.");
            }
        };

        final CommandExecutor executor;
        final ReplicationMethod replicationMethod = cfg.replicationConfig().method();
        switch (replicationMethod) {
            case ZOOKEEPER:
                executor = newZooKeeperCommandExecutor(pm, repositoryWorker, sessionManager,
                                                       onTakeLeadership, onReleaseLeadership);
                break;
            case NONE:
                logger.info("No replication mechanism specified; entering standalone");
                executor = new StandaloneCommandExecutor(pm, repositoryWorker, sessionManager,
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
                               @Nullable SessionManager sessionManager) {
        final ServerBuilder sb = new ServerBuilder();
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

        cfg.numWorkers().ifPresent(
                numWorkers -> sb.workerGroup(EventLoopGroups.newEventLoopGroup(numWorkers), true));
        cfg.maxNumConnections().ifPresent(sb::maxNumConnections);
        cfg.idleTimeoutMillis().ifPresent(sb::idleTimeoutMillis);
        cfg.requestTimeoutMillis().ifPresent(sb::defaultRequestTimeoutMillis);
        cfg.maxFrameLength().ifPresent(sb::defaultMaxRequestLength);
        cfg.gracefulShutdownTimeout().ifPresent(
                t -> sb.gracefulShutdownTimeout(t.quietPeriodMillis(), t.timeoutMillis()));

        final MetadataService mds = new MetadataService(pm, executor);
        final WatchService watchService = new WatchService();
        final AuthProvider authProvider = createAuthProvider(executor, sessionManager, mds);

        configureThriftService(sb, pm, executor, watchService, mds);

        sb.service("/hostname", HttpFileService.forVfs(new AbstractHttpVfs() {
            @Override
            public HttpFile get(String path, Clock clock, @Nullable String contentEncoding) {
                requireNonNull(path, "path");
                final Server s = server;
                assert s != null;
                final Builder<String, String> b = new Builder<>();
                b.put("hostname", s.defaultHostname());
                final String title = cfg.webAppTitle();
                if (!isNullOrEmpty(title)) {
                    b.put("title", title);
                }
                try {
                    return HttpFileBuilder.of(HttpData.ofUtf8(Jackson.writeValueAsString(b.build())))
                                          .setHeader(HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8)
                                          .build();
                } catch (JsonProcessingException e) {
                    throw new Error("Failed to send the hostname:", e);
                }
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

        sb.service(HEALTH_CHECK_PATH, new HttpHealthCheckService());

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

        configureHttpApi(sb, pm, executor, watchService, mds, authProvider, sessionManager);

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

    private CommandExecutor newZooKeeperCommandExecutor(ProjectManager pm, Executor repositoryWorker,
                                                        @Nullable SessionManager sessionManager,
                                                        @Nullable Consumer<CommandExecutor> onTakeLeadership,
                                                        @Nullable Runnable onReleaseLeadership) {
        final ZooKeeperReplicationConfig zkCfg = (ZooKeeperReplicationConfig) cfg.replicationConfig();

        // Delete the old UUID replica ID which is not used anymore.
        new File(cfg.dataDir(), "replica_id").delete();

        // TODO(trustin): Provide a way to restart/reload the replicator
        //                so that we can recover from ZooKeeper maintenance automatically.
        return new ZooKeeperCommandExecutor(zkCfg, cfg.dataDir(),
                                            new StandaloneCommandExecutor(pm,
                                                                          repositoryWorker,
                                                                          sessionManager,
                                                                          null, null),
                                            onTakeLeadership, onReleaseLeadership);
    }

    private void configureThriftService(ServerBuilder sb, ProjectManager pm, CommandExecutor executor,
                                        WatchService watchService, MetadataService mds) {
        final CentralDogmaServiceImpl service =
                new CentralDogmaServiceImpl(pm, executor, watchService, mds);

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

        // Enable content compression for API responses.
        thriftService = thriftService.decorate(contentEncodingDecorator());

        sb.service("/cd/thrift/v1", thriftService);
    }

    private void configureHttpApi(ServerBuilder sb,
                                  ProjectManager pm, CommandExecutor executor,
                                  WatchService watchService, MetadataService mds,
                                  @Nullable AuthProvider authProvider,
                                  @Nullable SessionManager sessionManager) {
        Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator;

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
            decorator = MetadataServiceInjector
                    .newDecorator(mds)
                    .andThen(new HttpAuthServiceBuilder()
                                     .add(new ApplicationTokenAuthorizer(mds::findTokenBySecret)
                                                  .orElse(new SessionTokenAuthorizer(sessionManager,
                                                                                     authCfg.administrators())))
                                     .newDecorator());
        } else {
            decorator = MetadataServiceInjector
                    .newDecorator(mds)
                    .andThen(HttpAuthService.newDecorator(new CsrfTokenAuthorizer()));
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
                            new ContentServiceV1(safePm, executor, watchService), decorator,
                            v1RequestConverter, v1ResponseConverter);

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
                    .ifPresent(login -> LOGIN_API_PATH_MAPPINGS.forEach(mapping -> sb.service(mapping, login)));

            // Provide logout API by default.
            final Service<HttpRequest, HttpResponse> logout =
                    Optional.ofNullable(authProvider.logoutApiService())
                            .orElseGet(() -> new DefaultLogoutService(executor));
            for (PathMapping mapping : LOGOUT_API_PATH_MAPPINGS) {
                sb.service(mapping, decorator.apply(logout));
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
                        HttpFileService.forClassPath("auth-webapp"), "/index.html"));
            }
            sb.serviceUnder("/", HttpFileService.forClassPath("webapp"));
        }
    }

    private static Function<Service<HttpRequest, HttpResponse>,
            HttpEncodingService> contentEncodingDecorator() {
        return delegate -> new HttpEncodingService(delegate, contentType -> {
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
        }, 1024); // Do not encode if content-length < 1024.
    }

    private void doStop() {
        if (server == null) {
            return;
        }

        final Server server = this.server;
        final CommandExecutor executor = this.executor;
        final DefaultMirroringService mirroringService = this.mirroringService;
        final ProjectManager pm = this.pm;
        final ExecutorService repositoryWorker = this.repositoryWorker;
        final SessionManager sessionManager = this.sessionManager;

        this.server = null;
        this.executor = null;
        this.mirroringService = null;
        this.pm = null;
        this.repositoryWorker = null;
        this.sessionManager = null;

        logger.info("Stopping the Central Dogma ..");
        if (!doStop(server, executor, mirroringService, pm, repositoryWorker, sessionManager)) {
            logger.warn("Stopped the Central Dogma with failure.");
        } else {
            logger.info("Stopped the Central Dogma successfully.");
        }
    }

    private static boolean doStop(
            @Nullable Server server, @Nullable CommandExecutor executor,
            @Nullable DefaultMirroringService mirroringService,
            @Nullable ProjectManager pm, @Nullable ExecutorService repositoryWorker,
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

        try {
            // Stop the mirroring service if the command executor did not stop it.
            if (mirroringService != null && mirroringService.isStarted()) {
                logger.info("Stopping the mirroring service not terminated by the command executor ..");
                mirroringService.stop();
                logger.info("Stopped the mirroring service.");
            }
        } catch (Throwable t) {
            success = false;
            logger.warn("Failed to stop the mirroring service:", t);
        }

        try {
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
                logger.info("Stopped the repository worker.");

                if (interruptLater) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Throwable t) {
            success = false;
            logger.warn("Failed to stop the repository worker:", t);
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

    private final class CentralDogmaStartStop extends StartStopSupport<Void, Void> {

        CentralDogmaStartStop() {
            super(GlobalEventExecutor.INSTANCE);
        }

        @Override
        protected CompletionStage<Void> doStart() throws Exception {
            return execute("startup", () -> {
                try {
                    CentralDogma.this.doStart();
                } catch (Exception e) {
                    Exceptions.throwUnsafely(e);
                }
            });
        }

        @Override
        protected CompletionStage<Void> doStop() throws Exception {
            return execute("shutdown", CentralDogma.this::doStop);
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
