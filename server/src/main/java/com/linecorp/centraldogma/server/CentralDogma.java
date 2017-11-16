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

import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.initializeInternalProject;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.shiro.cache.MemoryConstrainedCacheManager;
import org.apache.shiro.config.Ini;
import org.apache.shiro.config.IniSecurityManagerFactory;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.session.mgt.DefaultSessionManager;
import org.apache.shiro.session.mgt.SessionManager;
import org.apache.shiro.util.Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.auth.HttpAuthService;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.file.HttpFileService;
import com.linecorp.armeria.server.file.HttpVfs.ByteArrayEntry;
import com.linecorp.armeria.server.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftCallService;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;
import com.linecorp.centraldogma.server.internal.admin.authentication.ApplicationTokenAuthorizer;
import com.linecorp.centraldogma.server.internal.admin.authentication.CentralDogmaSessionDAO;
import com.linecorp.centraldogma.server.internal.admin.authentication.CsrfTokenAuthorizer;
import com.linecorp.centraldogma.server.internal.admin.authentication.LoginService;
import com.linecorp.centraldogma.server.internal.admin.authentication.LogoutService;
import com.linecorp.centraldogma.server.internal.admin.authentication.SessionTokenAuthorizer;
import com.linecorp.centraldogma.server.internal.admin.service.ProjectService;
import com.linecorp.centraldogma.server.internal.admin.service.RepositoryService;
import com.linecorp.centraldogma.server.internal.admin.service.TokenService;
import com.linecorp.centraldogma.server.internal.admin.service.UserService;
import com.linecorp.centraldogma.server.internal.admin.util.RestfulJsonResponseConverter;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.command.ProjectInitializingCommandExecutor;
import com.linecorp.centraldogma.server.internal.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.mirror.DefaultMirroringService;
import com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaExceptionTranslator;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaServiceImpl;
import com.linecorp.centraldogma.server.internal.thrift.CentralDogmaTimeoutScheduler;
import com.linecorp.centraldogma.server.internal.thrift.TokenlessClientLogger;

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
    private CentralDogmaSessionManager sessionManager;
    private SecurityManager securityManager;

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
        CentralDogmaSessionManager sessionManager = null;
        SecurityManager securityManager = null;
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
                sessionManager = new CentralDogmaSessionManager();
                securityManager = createSecurityManager(securityConfig, sessionManager);
            }

            logger.info("Starting the command executor ..");
            executor = startCommandExecutor(pm, mirroringService, repositoryWorker, sessionManager);
            logger.info("Started the command executor");

            initializeInternalProject(executor);

            if (cfg.isSecurityEnabled()) {
                sessionManager.setSessionDAO(new CentralDogmaSessionDAO(pm, executor));
                sessionManager.setSessionValidationInterval(Duration.ofMinutes(10).toMillis());
            }

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
                this.sessionManager = sessionManager;
                this.securityManager = securityManager;
            } else {
                stop(server, executor, mirroringService, pm, repositoryWorker);
            }
        }
    }

    private CommandExecutor startCommandExecutor(
            ProjectManager pm, DefaultMirroringService mirroringService, Executor repositoryWorker,
            CentralDogmaSessionManager sessionManager) {
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
                    if (sessionManager != null) {
                        sessionManager.onTakeLeadership();
                    }
                }, () -> {
                    logger.info("Stopping the mirroring service ..");
                    mirroringService.stop();
                    logger.info("Stopped the mirroring service");
                    if (sessionManager != null) {
                        sessionManager.onReleaseLeadership();
                    }
                });
            } else {
                projInitExecutor.start(() -> {
                    logger.info("Not starting the mirroring service because it's disabled.");
                    if (sessionManager != null) {
                        sessionManager.onTakeLeadership();
                    }
                }, () -> {
                    if (sessionManager != null) {
                        sessionManager.onReleaseLeadership();
                    }
                });
            }
        } catch (Exception e) {
            logger.warn("Failed to start the command executor. Entering read-only.", e);
        }

        return projInitExecutor;
    }

    private Server startServer(ProjectManager pm, CommandExecutor executor, SecurityManager securityManager) {
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

        configureThriftService(sb, pm, executor);

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

        sb.service("/security_enabled", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                    throws Exception {
                res.respond(HttpStatus.OK,
                            MediaType.JSON_UTF_8,
                            Jackson.writeValueAsPrettyString(cfg.isSecurityEnabled()));
            }
        });

        sb.service("/monitor/l7check", new HttpHealthCheckService());

        // TODO(hyangtack): This service is temporarily added to support redirection from '/docs' to '/docs/'.
        //                  It would be removed if this kind of redirection is handled by Armeria.
        sb.service("/docs", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                    throws Exception {
                res.respond(AggregatedHttpMessage.of(
                        HttpHeaders.of(HttpStatus.TEMPORARY_REDIRECT)
                                   .set(HttpHeaderNames.LOCATION, "/docs/")));
            }
        });
        sb.serviceUnder("/docs/",
                        new DocServiceBuilder().exampleHttpHeaders(
                                CentralDogmaService.class,
                                HttpHeaders.of(HttpHeaderNames.AUTHORIZATION,
                                               "bearer " + CsrfToken.ANONYMOUS))
                                               .build());

        if (cfg.isWebAppEnabled()) {
            configureWebAdmin(sb, pm, executor, securityManager);
        }

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

    private void configureThriftService(ServerBuilder sb, ProjectManager pm, CommandExecutor executor) {
        final CentralDogmaServiceImpl service =
                new CentralDogmaServiceImpl(pm, executor);

        sb.serverListener(new ServerListenerAdapter() {
            @Override
            public void serverStopping(Server server) {
                service.serverStopping();
            }
        });

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

    private void configureWebAdmin(ServerBuilder sb,
                                   ProjectManager pm, CommandExecutor executor,
                                   SecurityManager securityManager) {
        final String apiPathPrefix = "/api/v0/";

        final TokenService tokenService = new TokenService(pm, executor);

        final Function<Service<HttpRequest, HttpResponse>,
                        ? extends Service<HttpRequest, HttpResponse>> decorator;
        if (cfg.isSecurityEnabled()) {
            requireNonNull(securityManager, "securityManager");
            sb.service(apiPathPrefix + "authenticate", new LoginService(securityManager))
              .service(apiPathPrefix + "logout", new LogoutService(securityManager));

            final ApplicationTokenAuthorizer ata = new ApplicationTokenAuthorizer(tokenService::findToken);
            final SessionTokenAuthorizer sta = new SessionTokenAuthorizer(securityManager);

            decorator = HttpAuthService.newDecorator(ata, sta);
        } else {
            decorator = HttpAuthService.newDecorator(new CsrfTokenAuthorizer());
        }

        final Map<Class<?>, ResponseConverter> converters = ImmutableMap.of(
                Object.class, new RestfulJsonResponseConverter()  // Default converter
        );

        // TODO(hyangtack): Simplify this if https://github.com/line/armeria/issues/582 is resolved.
        sb.annotatedService(apiPathPrefix, new UserService(pm, executor), converters, decorator)
          .annotatedService(apiPathPrefix, new ProjectService(pm, executor), converters, decorator)
          .annotatedService(apiPathPrefix, new RepositoryService(pm, executor), converters, decorator)
          .annotatedService(apiPathPrefix, tokenService, converters, decorator)
          .serviceUnder("/", HttpFileService.forClassPath("webapp"));
    }

    private static SecurityManager createSecurityManager(Ini securityConfig, SessionManager sessionManager) {
        final Factory<SecurityManager> factory = new IniSecurityManagerFactory(securityConfig) {
            @Override
            protected SecurityManager createDefaultInstance() {
                DefaultSecurityManager securityManager = new DefaultSecurityManager();
                securityManager.setSessionManager(sessionManager);
                securityManager.setCacheManager(new MemoryConstrainedCacheManager());
                return securityManager;
            }
        };
        return factory.getInstance();
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
        this.sessionManager = null;
        this.securityManager = null;

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

    /**
     * A {@link SessionManager} which makes it possible to call
     * {@link DefaultSessionManager#enableSessionValidation()} and
     * {@link DefaultSessionManager#disableSessionValidation()} according to this server's leadership.
     */
    static final class CentralDogmaSessionManager extends DefaultSessionManager {

        void onTakeLeadership() {
            logger.info("Starting the session service ..");
            setSessionValidationSchedulerEnabled(true);
            enableSessionValidation();
            logger.info("Started the session service");
        }

        void onReleaseLeadership() {
            logger.info("Stopping the session service ..");
            setSessionValidationSchedulerEnabled(false);
            disableSessionValidation();
            logger.info("Stopped the session service");
        }
    }
}
