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

import static com.linecorp.centraldogma.server.metadata.PerRolePermissions.NO_PERMISSION;
import static com.linecorp.centraldogma.server.metadata.PerRolePermissions.READ_ONLY;
import static com.linecorp.centraldogma.server.metadata.PerRolePermissions.READ_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.api.HttpApiExceptionHandler;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectInitializer;
import com.linecorp.centraldogma.server.management.ServerStatusManager;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.MetadataServiceInjector;
import com.linecorp.centraldogma.server.metadata.PerRolePermissions;
import com.linecorp.centraldogma.server.metadata.Permission;
import com.linecorp.centraldogma.server.metadata.ProjectRole;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;

class PermissionTest {

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
                    MoreExecutors.directExecutor(), NoopMeterRegistry.get(), null);
            final ServerStatusManager statusManager = new ServerStatusManager(dataDir);
            final CommandExecutor executor = new StandaloneCommandExecutor(
                    pm, ForkJoinPool.commonPool(), statusManager, null, null, null);
            executor.start().join();

            ProjectInitializer.initializeInternalProject(executor);

            executor.execute(Command.createProject(AUTHOR, "project1")).join();

            final MetadataService mds = new MetadataService(pm, executor);

            mds.createToken(AUTHOR, APP_ID_1, SECRET_1).toCompletableFuture().join();
            mds.createToken(AUTHOR, APP_ID_2, SECRET_2).toCompletableFuture().join();
            mds.createToken(AUTHOR, APP_ID_3, SECRET_3).toCompletableFuture().join();

            mds.addRepo(AUTHOR, "project1", "repo1",
                        new PerRolePermissions(READ_ONLY, READ_ONLY, NO_PERMISSION, NO_PERMISSION))
               .toCompletableFuture().join();
            mds.addRepo(AUTHOR, "project1", "anonymous_allowed_repo",
                        new PerRolePermissions(READ_ONLY, READ_ONLY, NO_PERMISSION, READ_ONLY))
               .toCompletableFuture().join();

            // app-1 is an owner and it has read/write permission.
            mds.addToken(AUTHOR, "project1", APP_ID_1, ProjectRole.OWNER)
               .toCompletableFuture().join();
            mds.addPerTokenPermission(AUTHOR, "project1", "repo1", APP_ID_1,
                                      READ_WRITE)
               .toCompletableFuture().join();

            // app-2 is a member and it has read-only permission.
            mds.addToken(AUTHOR, "project1", APP_ID_2, ProjectRole.MEMBER)
               .toCompletableFuture().join();

            final Function<? super HttpService, ? extends HttpService> decorator =
                    MetadataServiceInjector.newDecorator(mds).andThen(AuthService.newDecorator(
                            new ApplicationTokenAuthorizer(mds::findTokenBySecret)));
            sb.annotatedService(new Object() {
                @Get("/projects/{projectName}")
                @RequiresRole(roles = { ProjectRole.OWNER, ProjectRole.MEMBER })
                public HttpResponse project(@Param String projectName) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Post("/projects/{projectName}/repos/{repoName}")
                @RequiresWritePermission
                public HttpResponse write(@Param String projectName,
                                          @Param String repoName) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Get("/projects/{projectName}/repos/{repoName}")
                @RequiresReadPermission
                public HttpResponse read(@Param String projectName,
                                         @Param String repoName) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            }, decorator, new HttpApiExceptionHandler());
        }
    };

    @ParameterizedTest
    @MethodSource("arguments")
    void test(@Nullable String appId, String secret, String projectName, ProjectRole role, String repoName,
              Set<Permission> permission, HttpStatus expectedFailureStatus) throws InterruptedException {
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
        assertThat(response.status()).isEqualTo(permission.contains(Permission.WRITE) ? HttpStatus.OK
                                                                                      : expectedFailureStatus);
        if (appId == null) {
            assertThat(authenticatedUser(server.requestContextCaptor().take())).isNull();
        } else {
            assertThat(authenticatedUser(server.requestContextCaptor().take())).isEqualTo(appId);
        }

        response = client.get("/projects/" + projectName + "/repos/" + repoName)
                         .aggregate().join();
        assertThat(response.status()).isEqualTo(permission.isEmpty() ? expectedFailureStatus
                                                                     : HttpStatus.OK);
        if (appId == null) {
            assertThat(authenticatedUser(server.requestContextCaptor().take())).isNull();
        } else {
            assertThat(authenticatedUser(server.requestContextCaptor().take())).isEqualTo(appId);
        }
    }

    @Test
    void test_anonymous() {
        final BlockingWebClient client = server.blockingWebClient(
                builder -> builder.addHeader(HttpHeaderNames.AUTHORIZATION,
                                             "Bearer " + CsrfToken.ANONYMOUS));
        final AggregatedHttpResponse response = client.get("/projects/project1/repos/anonymous_allowed_repo");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        final WebClient client2 = WebClient.builder(server.httpUri()).build();
        final AggregatedHttpResponse response2 = client2.get("/projects/project1/repos/anonymous_allowed_repo")
                                                  .aggregate().join();
        assertThat(response2.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Nullable
    private static String authenticatedUser(ServiceRequestContext take) {
        return take.log().whenComplete().join().authenticatedUser();
    }

    private static Stream<Arguments> arguments() {
        return Stream.of(
                Arguments.of(
                        "app/" + APP_ID_1, SECRET_1,
                        "project1", ProjectRole.OWNER, "repo1", READ_WRITE,
                        HttpStatus.FORBIDDEN),
                Arguments.of(
                        "app/" + APP_ID_2, SECRET_2,
                        "project1", ProjectRole.MEMBER, "repo1", READ_ONLY,
                        HttpStatus.FORBIDDEN),
                Arguments.of(
                        "app/" + APP_ID_3, SECRET_3,
                        "project1", ProjectRole.GUEST, "repo1", NO_PERMISSION,
                        HttpStatus.FORBIDDEN),
                Arguments.of(
                        "app/" + APP_ID_1, SECRET_1,
                        "project2", ProjectRole.GUEST, "repo1", NO_PERMISSION,
                        HttpStatus.NOT_FOUND),
                Arguments.of(
                        null, "appToken-invalid",
                        "project1", ProjectRole.GUEST, "repo1", NO_PERMISSION,
                        // Need to authorize because the token is invalid.
                        HttpStatus.UNAUTHORIZED));
    }
}
