/*
 * Copyright 2018 LINE Corporation
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

import static com.linecorp.centraldogma.server.internal.metadata.PerRolePermissions.NO_PERMISSION;
import static com.linecorp.centraldogma.server.internal.metadata.PerRolePermissions.READ_ONLY;
import static com.linecorp.centraldogma.server.internal.metadata.PerRolePermissions.READ_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.auth.HttpAuthService;
import com.linecorp.armeria.testing.server.ServerRule;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.api.HttpApiExceptionHandler;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.command.ProjectInitializer;
import com.linecorp.centraldogma.server.internal.command.ProjectInitializingCommandExecutor;
import com.linecorp.centraldogma.server.internal.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.metadata.MetadataService;
import com.linecorp.centraldogma.server.internal.metadata.MetadataServiceInjector;
import com.linecorp.centraldogma.server.internal.metadata.MigrationUtil;
import com.linecorp.centraldogma.server.internal.metadata.PerRolePermissions;
import com.linecorp.centraldogma.server.internal.metadata.Permission;
import com.linecorp.centraldogma.server.internal.metadata.ProjectRole;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

@RunWith(Parameterized.class)
public class PermissionTest {

    private static final Author author = Author.SYSTEM;

    private static final String secret1 = "appToken-1";
    private static final String secret2 = "appToken-2";
    private static final String secret3 = "appToken-3";

    protected static TemporaryFolder initStaticTemp() {
        try {
            return new TemporaryFolder() {
                {
                    before();
                }
            };
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static final TemporaryFolder rootDir = initStaticTemp();

    @AfterClass
    public static void cleanup() throws Exception {
        rootDir.delete();
    }

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final ProjectManager pm = new DefaultProjectManager(
                    rootDir.newFolder(), ForkJoinPool.commonPool(), null);
            final CommandExecutor executor = new ProjectInitializingCommandExecutor(
                    new StandaloneCommandExecutor(pm, null, ForkJoinPool.commonPool()));
            executor.start(null, null);

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

            final Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>> decorator =
                    MetadataServiceInjector.newDecorator(mds).andThen(HttpAuthService.newDecorator(
                            new ApplicationTokenAuthorizer(mds::findTokenBySecret)));
            sb.annotatedService(new Object() {
                @Get("/projects/{projectName}")
                @Decorator(ProjectMembersOnly.class)
                public HttpResponse project(@Param("projectName") String projectName) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Post("/projects/{projectName}/repos/{repoName}")
                @Decorator(HasWritePermission.class)
                public HttpResponse write(@Param("projectName") String projectName,
                                          @Param("repoName") String repoName) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Get("/projects/{projectName}/repos/{repoName}")
                @Decorator(HasReadPermission.class)
                public HttpResponse read(@Param("projectName") String projectName,
                                         @Param("repoName") String repoName) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            }, decorator, new HttpApiExceptionHandler());
        }
    };

    private final String secret;
    private final String projectName;
    private final ProjectRole role;
    private final String repoName;
    private final Set<Permission> permission;
    private final HttpStatus expectedFailureStatus;

    public PermissionTest(String secret, String projectName, ProjectRole role,
                          String repoName, Set<Permission> permission,
                          HttpStatus expectedFailureStatus) {
        this.secret = secret;
        this.projectName = projectName;
        this.role = role;
        this.repoName = repoName;
        this.permission = EnumSet.copyOf(permission);
        this.expectedFailureStatus = expectedFailureStatus;
    }

    @Parameters
    public static Object[][] parameters() {
        return new Object[][] {
                {
                        secret1,
                        "project1", ProjectRole.OWNER, "repo1", READ_WRITE,
                        HttpStatus.FORBIDDEN
                },
                {
                        secret2,
                        "project1", ProjectRole.MEMBER, "repo1", READ_ONLY,
                        HttpStatus.FORBIDDEN
                },
                {
                        secret3,
                        "project1", ProjectRole.GUEST, "repo1", NO_PERMISSION,
                        HttpStatus.FORBIDDEN
                },
                {
                        secret1,
                        "project2", ProjectRole.GUEST, "repo1", NO_PERMISSION,
                        HttpStatus.NOT_FOUND
                },
                {
                        "appToken-invalid",
                        "project1", ProjectRole.GUEST, "repo1", NO_PERMISSION,
                        // Need to authorize because the token is invalid.
                        HttpStatus.UNAUTHORIZED
                }
        };
    }

    @Test
    public void test() {
        final HttpClient client = new HttpClientBuilder(rule.uri("/"))
                .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "bearer " + secret).build();

        AggregatedHttpMessage response;

        response = client.get("/projects/" + projectName).aggregate().join();
        assertThat(response.status())
                .isEqualTo(role == ProjectRole.OWNER || role == ProjectRole.MEMBER ? HttpStatus.OK
                                                                                   : expectedFailureStatus);

        response = client.post("/projects/" + projectName + "/repos/" + repoName, HttpData.EMPTY_DATA)
                         .aggregate().join();
        assertThat(response.status()).isEqualTo(permission.contains(Permission.WRITE) ? HttpStatus.OK
                                                                                      : expectedFailureStatus);

        response = client.get("/projects/" + projectName + "/repos/" + repoName)
                         .aggregate().join();
        assertThat(response.status()).isEqualTo(permission.isEmpty() ? expectedFailureStatus
                                                                     : HttpStatus.OK);
    }
}
