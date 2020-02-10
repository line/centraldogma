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

import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.api.HttpApiExceptionHandler;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectInitializer;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.MetadataServiceInjector;
import com.linecorp.centraldogma.server.metadata.MigrationUtil;
import com.linecorp.centraldogma.server.metadata.PerRolePermissions;
import com.linecorp.centraldogma.server.metadata.Permission;
import com.linecorp.centraldogma.server.metadata.ProjectRole;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;

class PermissionTest {

    private static final Author author = Author.SYSTEM;

    private static final String secret1 = "appToken-1";
    private static final String secret2 = "appToken-2";
    private static final String secret3 = "appToken-3";

    @Order(1)
    @RegisterExtension
    static final TemporaryFolderExtension rootDir = new TemporaryFolderExtension();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final ProjectManager pm = new DefaultProjectManager(
                    rootDir.getRoot().toFile(), ForkJoinPool.commonPool(),
                    MoreExecutors.directExecutor(), NoopMeterRegistry.get(), null);
            final CommandExecutor executor = new StandaloneCommandExecutor(
                    pm, ForkJoinPool.commonPool(), null, null, null);
            executor.start().join();

            ProjectInitializer.initializeInternalProject(executor);
            MigrationUtil.migrate(pm, executor);

            executor.execute(Command.createProject(author, "project1")).join();

            final MetadataService mds = new MetadataService(pm, executor);

            mds.createToken(author, "app-1", secret1).toCompletableFuture().join();
            mds.createToken(author, "app-2", secret2).toCompletableFuture().join();
            mds.createToken(author, "app-3", secret3).toCompletableFuture().join();

            mds.addRepo(author, "project1", "repo1",
                        new PerRolePermissions(READ_ONLY, READ_ONLY, NO_PERMISSION))
               .toCompletableFuture().join();

            // app-1 is an owner and it has read/write permission.
            mds.addToken(author, "project1", "app-1", ProjectRole.OWNER)
               .toCompletableFuture().join();
            mds.addPerTokenPermission(author, "project1", "repo1", "app-1",
                                      READ_WRITE)
               .toCompletableFuture().join();

            // app-2 is a member and it has read-only permission.
            mds.addToken(author, "project1", "app-2", ProjectRole.MEMBER)
               .toCompletableFuture().join();

            final Function<? super HttpService, ? extends HttpService> decorator =
                    MetadataServiceInjector.newDecorator(mds).andThen(AuthService.newDecorator(
                            new ApplicationTokenAuthorizer(mds::findTokenBySecret)));
            sb.annotatedService(new Object() {
                @Get("/projects/{projectName}")
                @RequiresRole(roles = { ProjectRole.OWNER, ProjectRole.MEMBER })
                public HttpResponse project(@Param("projectName") String projectName) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Post("/projects/{projectName}/repos/{repoName}")
                @RequiresWritePermission
                public HttpResponse write(@Param("projectName") String projectName,
                                          @Param("repoName") String repoName) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Get("/projects/{projectName}/repos/{repoName}")
                @RequiresReadPermission
                public HttpResponse read(@Param("projectName") String projectName,
                                         @Param("repoName") String repoName) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            }, decorator, new HttpApiExceptionHandler());
        }
    };

    @ParameterizedTest
    @MethodSource("arguments")
    void test(String secret, String projectName, ProjectRole role, String repoName,
              Set<Permission> permission, HttpStatus expectedFailureStatus) {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "Bearer " + secret)
                                          .build();

        AggregatedHttpResponse response;

        response = client.get("/projects/" + projectName).aggregate().join();
        assertThat(response.status())
                .isEqualTo(role == ProjectRole.OWNER || role == ProjectRole.MEMBER ? HttpStatus.OK
                                                                                   : expectedFailureStatus);

        response = client.post("/projects/" + projectName + "/repos/" + repoName, HttpData.empty())
                         .aggregate().join();
        assertThat(response.status()).isEqualTo(permission.contains(Permission.WRITE) ? HttpStatus.OK
                                                                                      : expectedFailureStatus);

        response = client.get("/projects/" + projectName + "/repos/" + repoName)
                         .aggregate().join();
        assertThat(response.status()).isEqualTo(permission.isEmpty() ? expectedFailureStatus
                                                                     : HttpStatus.OK);
    }

    private static Stream<Arguments> arguments() {
        return Stream.of(
                Arguments.of(
                        secret1,
                        "project1", ProjectRole.OWNER, "repo1", READ_WRITE,
                        HttpStatus.FORBIDDEN),
                Arguments.of(
                        secret2,
                        "project1", ProjectRole.MEMBER, "repo1", READ_ONLY,
                        HttpStatus.FORBIDDEN),
                Arguments.of(
                        secret3,
                        "project1", ProjectRole.GUEST, "repo1", NO_PERMISSION,
                        HttpStatus.FORBIDDEN),
                Arguments.of(
                        secret1,
                        "project2", ProjectRole.GUEST, "repo1", NO_PERMISSION,
                        HttpStatus.NOT_FOUND),
                Arguments.of(
                        "appToken-invalid",
                        "project1", ProjectRole.GUEST, "repo1", NO_PERMISSION,
                        // Need to authorize because the token is invalid.
                        HttpStatus.UNAUTHORIZED));
    }
}
