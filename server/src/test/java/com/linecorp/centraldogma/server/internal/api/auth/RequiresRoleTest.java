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

package com.linecorp.centraldogma.server.internal.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.api.HttpApiExceptionHandler;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresProjectRoleDecorator.RequiresProjectRoleDecoratorFactory;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRepositoryRoleDecorator.RequiresRepositoryRoleDecoratorFactory;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.management.ServerStatusManager;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.ProjectRoles;
import com.linecorp.centraldogma.server.storage.encryption.NoopEncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;

class RequiresRoleTest {

    private static final Author AUTHOR = Author.SYSTEM;

    private static final String APP_ID_1 = "app-1";
    private static final String SECRET_1 = "appToken-1";
    private static final String APP_ID_2 = "app-2";
    private static final String SECRET_2 = "appToken-2";
    private static final String APP_ID_3 = "app-3";
    private static final String SECRET_3 = "appToken-3";

    @Order(1)
    @RegisterExtension
    static final TemporaryFolderExtension rootDir = new TemporaryFolderExtension();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File dataDir = rootDir.getRoot().toFile();
            final ProjectManager pm = new DefaultProjectManager(
                    dataDir, ForkJoinPool.commonPool(),
                    MoreExecutors.directExecutor(), NoopMeterRegistry.get(), null,
                    NoopEncryptionStorageManager.INSTANCE);
            final ServerStatusManager statusManager = new ServerStatusManager(dataDir);
            final CommandExecutor executor = new StandaloneCommandExecutor(
                    pm, ForkJoinPool.commonPool(), statusManager, null, NoopEncryptionStorageManager.INSTANCE,
                    null, null, null, null);
            executor.start().join();
            final InternalProjectInitializer projectInitializer = new InternalProjectInitializer(
                    executor, pm);
            projectInitializer.initialize();

            executor.execute(Command.createProject(AUTHOR, "project1")).join();

            final MetadataService mds = new MetadataService(pm, executor, projectInitializer);

            mds.createToken(AUTHOR, APP_ID_1, SECRET_1).toCompletableFuture().join();
            mds.createToken(AUTHOR, APP_ID_2, SECRET_2).toCompletableFuture().join();
            mds.createToken(AUTHOR, APP_ID_3, SECRET_3).toCompletableFuture().join();

            mds.addRepo(AUTHOR, "project1", "repo1", ProjectRoles.of(RepositoryRole.READ, null))
               .toCompletableFuture().join();

            // app-1 is an owner and it has read/write permission.
            mds.addToken(AUTHOR, "project1", APP_ID_1, ProjectRole.OWNER)
               .toCompletableFuture().join();
            await().until(() -> mds.findTokenByAppId(APP_ID_1) != null);
            mds.addTokenRepositoryRole(AUTHOR, "project1", "repo1", APP_ID_1, RepositoryRole.WRITE)
               .toCompletableFuture().join();

            // app-2 is a member and it has read-only permission.
            mds.addToken(AUTHOR, "project1", APP_ID_2, ProjectRole.MEMBER)
               .toCompletableFuture().join();
            await().until(() -> mds.findTokenByAppId(APP_ID_2) != null);
            sb.dependencyInjector(
                    DependencyInjector.ofSingletons(new RequiresRepositoryRoleDecoratorFactory(mds),
                                                    new RequiresProjectRoleDecoratorFactory(mds)),
                    false);
            sb.annotatedService(new Object() {
                @Get("/projects/{projectName}")
                @RequiresProjectRole(ProjectRole.MEMBER)
                public HttpResponse project(@Param String projectName) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Post("/projects/{projectName}/repos/{repoName}")
                @RequiresRepositoryRole(RepositoryRole.WRITE)
                public HttpResponse write(@Param String projectName,
                                          @Param String repoName) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Get("/projects/{projectName}/repos/{repoName}")
                @RequiresRepositoryRole(RepositoryRole.READ)
                public HttpResponse read(@Param String projectName,
                                         @Param String repoName) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            }, AuthService.newDecorator(new ApplicationTokenAuthorizer(mds::findTokenBySecret)));

            sb.errorHandler(new HttpApiExceptionHandler());
        }
    };

    @ParameterizedTest
    @MethodSource("arguments")
    void test(@Nullable String appId, String secret, String projectName, ProjectRole role, String repoName,
              @Nullable RepositoryRole repositoryRole,
              HttpStatus expectedFailureStatus) throws InterruptedException {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer " + secret)
                                          .build();

        AggregatedHttpResponse response;

        response = client.get("/projects/" + projectName).aggregate().join();
        assertThat(response.status())
                .isEqualTo(role == ProjectRole.OWNER || role == ProjectRole.MEMBER ? HttpStatus.OK
                                                                                   : expectedFailureStatus);
        if (appId == null) {
            assertThat(authenticatedUser(server.requestContextCaptor().take())).isNull();
        } else {
            assertThat(authenticatedUser(server.requestContextCaptor().take())).isEqualTo(appId);
        }

        response = client.post("/projects/" + projectName + "/repos/" + repoName, HttpData.empty())
                         .aggregate().join();
        assertThat(response.status()).isEqualTo(repositoryRole == RepositoryRole.WRITE ? HttpStatus.OK
                                                                                       : expectedFailureStatus);
        if (appId == null) {
            assertThat(authenticatedUser(server.requestContextCaptor().take())).isNull();
        } else {
            assertThat(authenticatedUser(server.requestContextCaptor().take())).isEqualTo(appId);
        }

        response = client.get("/projects/" + projectName + "/repos/" + repoName)
                         .aggregate().join();
        assertThat(response.status()).isEqualTo(repositoryRole == null ? expectedFailureStatus
                                                                       : HttpStatus.OK);
        if (appId == null) {
            assertThat(authenticatedUser(server.requestContextCaptor().take())).isNull();
        } else {
            assertThat(authenticatedUser(server.requestContextCaptor().take())).isEqualTo(appId);
        }
    }

    @Nullable
    private static String authenticatedUser(ServiceRequestContext take) {
        return take.log().whenComplete().join().authenticatedUser();
    }

    private static Stream<Arguments> arguments() {
        return Stream.of(
                Arguments.of(
                        "app/" + APP_ID_1, SECRET_1,
                        "project1", ProjectRole.OWNER, "repo1", RepositoryRole.WRITE,
                        HttpStatus.FORBIDDEN),
                Arguments.of(
                        "app/" + APP_ID_2, SECRET_2,
                        "project1", ProjectRole.MEMBER, "repo1", RepositoryRole.READ,
                        HttpStatus.FORBIDDEN),
                Arguments.of(
                        "app/" + APP_ID_3, SECRET_3,
                        "project1", ProjectRole.GUEST, "repo1", null,
                        HttpStatus.FORBIDDEN),
                Arguments.of(
                        "app/" + APP_ID_1, SECRET_1,
                        "project2", ProjectRole.GUEST, "repo1", null,
                        HttpStatus.NOT_FOUND),
                Arguments.of(
                        null, "appToken-invalid",
                        "project1", ProjectRole.GUEST, "repo1", null,
                        // Need to authorize because the token is invalid.
                        HttpStatus.UNAUTHORIZED));
    }
}
