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
package com.linecorp.centraldogma.server.internal.api;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.util.Collection;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.api.sysadmin.ApplicationLevelRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.ApplicationRegistryService;
import com.linecorp.centraldogma.server.metadata.Application;
import com.linecorp.centraldogma.server.metadata.ApplicationCertificate;
import com.linecorp.centraldogma.server.metadata.ApplicationRegistry;
import com.linecorp.centraldogma.server.metadata.ApplicationType;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.netty.util.internal.StringUtil;

class ApplicationRegistryServiceTest {

    @RegisterExtension
    static final ProjectManagerExtension manager = new ProjectManagerExtension();

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    private static final Author systemAdminAuthor = Author.ofEmail("systemAdmin@localhost.com");
    private static final Author guestAuthor = Author.ofEmail("guest@localhost.com");
    private static final User systemAdmin = new User("systemAdmin@localhost.com", User.LEVEL_SYSTEM_ADMIN);
    private static final User guest = new User("guest@localhost.com");
    private static final JsonNode activation = Jackson.valueToTree(
            ImmutableList.of(
                    ImmutableMap.of("op", "replace",
                                    "path", "/status",
                                    "value", "active")));
    private static final JsonNode deactivation = Jackson.valueToTree(
            ImmutableList.of(
                    ImmutableMap.of("op", "replace",
                                    "path", "/status",
                                    "value", "inactive")));

    private static ApplicationRegistryService applicationRegistryService;
    private static MetadataService metadataService;
    private static WebClient systemAdminClient;

    // ctx is only used for getting the blocking task executor.
    private final ServiceRequestContext ctx =
            ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();

    @BeforeAll
    static void setUp() throws JsonMappingException, JsonParseException {
        final URI uri = dogma.httpClient().uri();
        systemAdminClient = WebClient.builder(uri)
                                     .auth(AuthToken.ofOAuth2(getAccessToken(dogma.httpClient(),
                                                                             TestAuthMessageUtil.USERNAME,
                                                                             TestAuthMessageUtil.PASSWORD,
                                                                             true)))
                                     .build();
        metadataService = new MetadataService(manager.projectManager(), manager.executor(),
                                              manager.internalProjectInitializer());
        applicationRegistryService = new ApplicationRegistryService(manager.executor(), metadataService,
                                                                    true);
    }

    @AfterEach
    public void tearDown() {
        final ApplicationRegistry registry = metadataService.fetchApplicationRegistry().join();
        registry.appIds().forEach((appId, application) -> {
            if (!application.isDeleted()) {
                if (application.type() == ApplicationType.TOKEN) {
                    metadataService.destroyToken(systemAdminAuthor, appId);
                } else {
                    metadataService.destroyCertificate(systemAdminAuthor, appId);
                }
            }
            metadataService.purgeApplication(systemAdminAuthor, appId);
        });
    }

    @Test
    void systemAdminToken() {
        final Token token = applicationRegistryService.createToken("forAdmin1", true, null,
                                                                   systemAdminAuthor, systemAdmin).join()
                                                      .content();
        assertThat(token.isActive()).isTrue();
        assertThatThrownBy(
                () -> applicationRegistryService.createToken("forAdmin2", true, null, guestAuthor, guest)
                                                .join())
                .isInstanceOf(IllegalArgumentException.class);

        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) manager.executor();
        executor.execute(Command.createProject(Author.SYSTEM, "myPro")).join();
        metadataService.addApplication(Author.SYSTEM, "myPro", "forAdmin1", ProjectRole.OWNER).join();
        await().untilAsserted(() -> assertThat(metadataService.getProject("myPro").join().applications()
                                                              .containsKey("forAdmin1")).isTrue());

        final Collection<Token> tokens = applicationRegistryService.listTokens(systemAdmin);
        assertThat(tokens.stream().filter(t -> !StringUtil.isNullOrEmpty(t.secret()))).hasSize(1);

        assertThatThrownBy(() -> applicationRegistryService.deleteToken(ctx, "forAdmin1", guestAuthor, guest)
                                                           .join())
                .hasCauseInstanceOf(HttpResponseException.class);

        applicationRegistryService.deleteToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin).join();
        assertThat(applicationRegistryService.purgeToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin)
                                             .join())
                .satisfies(t -> {
                    assertThat(t.appId()).isEqualTo(token.appId());
                    assertThat(t.isSystemAdmin()).isEqualTo(token.isSystemAdmin());
                    assertThat(t.creation()).isEqualTo(token.creation());
                    assertThat(t.isDeleted()).isTrue();
                });
        await().untilAsserted(() -> assertThat(
                applicationRegistryService.listTokens(systemAdmin).size()).isEqualTo(0));
        assertThat(metadataService.getProject("myPro").join().applications().size()).isEqualTo(0);
    }

    @Test
    void systemAdminApplication() {
        final ApplicationCertificate certificate =
                (ApplicationCertificate) applicationRegistryService.createApplication(
                        "certAdmin1", true, ApplicationType.CERTIFICATE, null, "cert/123",
                        systemAdminAuthor, systemAdmin).join().content();
        assertThat(certificate.isActive()).isTrue();
        assertThat(certificate.certificateId()).isEqualTo("cert/123");
        assertThatThrownBy(
                () -> applicationRegistryService.createApplication("certAdmin2", true,
                                                                   ApplicationType.CERTIFICATE, null,
                                                                   "cert-456", guestAuthor, guest).join())
                .isInstanceOf(IllegalArgumentException.class);

        final Token token = (Token) applicationRegistryService.createApplication(
                "tokenAdmin1", true, ApplicationType.TOKEN, null, null,
                systemAdminAuthor, systemAdmin).join().content();
        assertThat(token.isActive()).isTrue();
        assertThat(token.secret()).isNotNull();

        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) manager.executor();
        executor.execute(Command.createProject(Author.SYSTEM, "certPro")).join();
        metadataService.addApplication(Author.SYSTEM, "certPro", "certAdmin1", ProjectRole.OWNER).join();
        await().untilAsserted(() -> assertThat(metadataService.getProject("certPro").join().applications()
                                                              .containsKey("certAdmin1")).isTrue());

        final ApplicationRegistry applicationRegistry = metadataService.getApplicationRegistry();
        assertThat(applicationRegistry.appIds().keySet()).containsExactlyInAnyOrder(
                "certAdmin1", "tokenAdmin1");
        assertThat(applicationRegistry.secrets()).hasSize(1);
        applicationRegistry.secrets().values().forEach(
                tokenAppId -> assertThat(tokenAppId).isEqualTo("tokenAdmin1"));
        assertThat(applicationRegistry.certificateIds()).contains(Maps.immutableEntry(
                "cert/123", "certAdmin1"));

        final Collection<Application> applications =
                applicationRegistryService.listApplications(systemAdmin)
                                          .stream()
                                          .collect(toImmutableList());
        assertThat(applications).contains(certificate, token);

        assertThatThrownBy(
                () -> applicationRegistryService.deleteApplication(ctx, "certAdmin1", guestAuthor, guest)
                                                .join())
                .hasCauseInstanceOf(HttpResponseException.class);

        final ApplicationCertificate deleted =
                (ApplicationCertificate) applicationRegistryService.deleteApplication(
                        ctx, "certAdmin1", systemAdminAuthor, systemAdmin).join();
        assertThat(deleted.appId()).isEqualTo(certificate.appId());

        assertThat(applicationRegistryService.purgeApplication(ctx, "certAdmin1", systemAdminAuthor,
                                                               systemAdmin).join())
                .satisfies(c -> {
                    assertThat(c.appId()).isEqualTo(certificate.appId());
                    assertThat(c.isSystemAdmin()).isEqualTo(certificate.isSystemAdmin());
                    assertThat(c.creation()).isEqualTo(certificate.creation());
                    assertThat(c.isDeleted()).isTrue();
                });
        await().untilAsserted(() -> assertThat(
                applicationRegistryService.listApplications(systemAdmin).stream()
                                          .filter(app -> app instanceof ApplicationCertificate)
                                          .count()).isEqualTo(0));
        assertThat(metadataService.getProject("certPro").join().applications().size()).isEqualTo(0);
    }

    @Test
    void userToken() {
        final Token userToken1 = applicationRegistryService.createToken("forUser1", false, null,
                                                                        systemAdminAuthor,
                                                                        systemAdmin)
                                                           .join().content();
        final Token userToken2 = applicationRegistryService.createToken("forUser2", false, null, guestAuthor,
                                                                        guest)
                                                           .join().content();
        assertThat(userToken1.isActive()).isTrue();
        assertThat(userToken2.isActive()).isTrue();

        final Collection<Token> tokens = applicationRegistryService.listTokens(guest);
        assertThat(tokens.stream().filter(token -> !StringUtil.isNullOrEmpty(token.secret())).count())
                .isEqualTo(0);

        assertThat(
                applicationRegistryService.deleteToken(ctx, "forUser1", systemAdminAuthor, systemAdmin)
                                          .thenCompose(unused -> applicationRegistryService.purgeToken(
                                                  ctx, "forUser1", systemAdminAuthor, systemAdmin)).join())
                .satisfies(t -> {
                    assertThat(t.appId()).isEqualTo(userToken1.appId());
                    assertThat(t.isSystemAdmin()).isEqualTo(userToken1.isSystemAdmin());
                    assertThat(t.creation()).isEqualTo(userToken1.creation());
                    assertThat(t.deactivation()).isEqualTo(userToken1.deactivation());
                });
        assertThat(
                applicationRegistryService.deleteToken(ctx, "forUser2", guestAuthor, guest)
                                          .thenCompose(unused -> applicationRegistryService.purgeToken(
                                                  ctx, "forUser2", guestAuthor, guest)).join())
                .satisfies(t -> {
                    assertThat(t.appId()).isEqualTo(userToken2.appId());
                    assertThat(t.isSystemAdmin()).isEqualTo(userToken2.isSystemAdmin());
                    assertThat(t.creation()).isEqualTo(userToken2.creation());
                    assertThat(t.deactivation()).isEqualTo(userToken2.deactivation());
                });
    }

    @Test
    void userCertificate() {
        final ApplicationCertificate userCert1 =
                (ApplicationCertificate) applicationRegistryService.createApplication(
                        "certUser1", false, ApplicationType.CERTIFICATE, null, "cert-user1",
                        systemAdminAuthor, systemAdmin).join().content();
        final ApplicationCertificate userCert2 =
                (ApplicationCertificate) applicationRegistryService.createApplication(
                        "certUser2", false, ApplicationType.CERTIFICATE, null, "cert-user2",
                        guestAuthor, guest).join().content();
        assertThat(userCert1.isActive()).isTrue();
        assertThat(userCert2.isActive()).isTrue();
        assertThat(userCert1.certificateId()).isEqualTo("cert-user1");
        assertThat(userCert2.certificateId()).isEqualTo("cert-user2");

        final Collection<ApplicationCertificate> certificates =
                applicationRegistryService.listApplications(guest).stream()
                                          .filter(app -> app instanceof ApplicationCertificate)
                                          .map(app -> (ApplicationCertificate) app)
                                          .collect(toImmutableList());
        assertThat(certificates.size()).isEqualTo(2);

        assertThat(applicationRegistryService
                           .deleteApplication(
                                   ctx, "certUser1", systemAdminAuthor, systemAdmin)
                           .thenCompose(unused -> applicationRegistryService.purgeApplication(
                                   ctx, "certUser1", systemAdminAuthor, systemAdmin)).join())
                .satisfies(c -> {
                    assertThat(c.appId()).isEqualTo(userCert1.appId());
                    assertThat(c.isSystemAdmin()).isEqualTo(userCert1.isSystemAdmin());
                    assertThat(c.creation()).isEqualTo(userCert1.creation());
                });
        assertThat(applicationRegistryService
                           .deleteApplication(ctx, "certUser2", guestAuthor, guest)
                           .thenCompose(unused -> applicationRegistryService.purgeApplication(
                                   ctx, "certUser2", guestAuthor, guest)).join())
                .satisfies(c -> {
                    assertThat(c.appId()).isEqualTo(userCert2.appId());
                    assertThat(c.isSystemAdmin()).isEqualTo(userCert2.isSystemAdmin());
                    assertThat(c.creation()).isEqualTo(userCert2.creation());
                });
    }

    @Test
    void nonRandomToken() {
        final Token token = applicationRegistryService.createToken("forAdmin1", true, "appToken-secret",
                                                                   systemAdminAuthor,
                                                                   systemAdmin)
                                                      .join().content();
        assertThat(token.isActive()).isTrue();

        final Collection<Token> tokens = applicationRegistryService.listTokens(systemAdmin);
        assertThat(tokens.stream().filter(t -> !StringUtil.isNullOrEmpty(t.secret()))).hasSize(1);

        assertThatThrownBy(() -> applicationRegistryService.createToken("forUser1", true,
                                                                        "appToken-secret", guestAuthor, guest)
                                                           .join())
                .isInstanceOf(IllegalArgumentException.class);

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.DELETE, "/tokens/{appId}/removed"));
        applicationRegistryService.deleteToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin).join();
        applicationRegistryService.purgeToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin).join();
    }

    @Test
    public void updateToken() {
        final Token token = applicationRegistryService.createToken("forUpdate", true, null,
                                                                   systemAdminAuthor, systemAdmin).join()
                                                      .content();
        assertThat(token.isActive()).isTrue();

        applicationRegistryService.updateToken(ctx, "forUpdate", deactivation, systemAdminAuthor, systemAdmin)
                                  .join();
        await().untilAsserted(() -> assertThat(metadataService.findApplicationByAppId("forUpdate").isActive())
                .isFalse());

        applicationRegistryService.updateToken(ctx, "forUpdate", activation, systemAdminAuthor, systemAdmin)
                                  .join();
        await().untilAsserted(() -> assertThat(metadataService.findApplicationByAppId("forUpdate").isActive())
                .isTrue());

        assertThatThrownBy(
                () -> applicationRegistryService.updateToken(ctx, "forUpdate", Jackson.valueToTree(
                        ImmutableList.of(ImmutableMap.of())), systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);

        applicationRegistryService.deleteToken(ctx, "forUpdate", systemAdminAuthor, systemAdmin).join();
        await().untilAsserted(() -> assertThat(metadataService.findApplicationByAppId("forUpdate").isDeleted())
                .isTrue());
        assertThatThrownBy(
                () -> applicationRegistryService.updateToken(ctx, "forUpdate", activation,
                                                             systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void updateCertificate() {
        final ApplicationCertificate certificate =
                (ApplicationCertificate) applicationRegistryService.createApplication(
                        "certUpdate", true, ApplicationType.CERTIFICATE, null, "cert/update",
                        systemAdminAuthor, systemAdmin).join().content();
        assertThat(certificate.isActive()).isTrue();

        final JsonNode deactivation = Jackson.valueToTree(ImmutableMap.of("status", "inactive"));
        final JsonNode activation = Jackson.valueToTree(ImmutableMap.of("status", "active"));
        applicationRegistryService.updateApplication(ctx, "certUpdate", deactivation,
                                                     systemAdminAuthor, systemAdmin).join();
        await().untilAsserted(() -> assertThat(
                metadataService.findApplicationByAppId("certUpdate").isActive()).isFalse());

        applicationRegistryService.updateApplication(ctx, "certUpdate", activation,
                                                     systemAdminAuthor, systemAdmin).join();
        await().untilAsserted(() -> assertThat(
                metadataService.findApplicationByAppId("certUpdate").isActive()).isTrue());

        assertThatThrownBy(
                () -> applicationRegistryService.updateApplication(ctx, "certUpdate", Jackson.valueToTree(
                        ImmutableList.of(ImmutableMap.of())), systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(IllegalArgumentException.class);

        applicationRegistryService.deleteApplication(ctx, "certUpdate", systemAdminAuthor, systemAdmin).join();
        await().untilAsserted(() -> assertThat(
                metadataService.findApplicationByAppId("certUpdate").isDeleted()).isTrue());
        assertThatThrownBy(
                () -> applicationRegistryService.updateApplication(ctx, "certUpdate", activation,
                                                                   systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateTokenLevel() {
        final Token token = applicationRegistryService.createToken("forUpdate", false, null,
                                                                   systemAdminAuthor, systemAdmin).join()
                                                      .content();
        assertThat(token.isActive()).isTrue();

        final Token userToken =
                applicationRegistryService.updateTokenLevel(
                                                  ctx, "forUpdate", new ApplicationLevelRequest("SYSTEMADMIN"),
                                                  systemAdminAuthor, systemAdmin)
                                          .join();
        assertThat(userToken.isSystemAdmin()).isTrue();

        final Token adminToken =
                applicationRegistryService.updateTokenLevel(
                                                  ctx, "forUpdate", new ApplicationLevelRequest("USER"),
                                                  systemAdminAuthor, systemAdmin)
                                          .join();
        assertThat(adminToken.isSystemAdmin()).isFalse();

        assertThatThrownBy(
                () -> applicationRegistryService.updateTokenLevel(
                        ctx, "forUpdate", new ApplicationLevelRequest("INVALID"),
                        systemAdminAuthor, systemAdmin).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateCertificateLevel() {
        final ApplicationCertificate certificate =
                (ApplicationCertificate) applicationRegistryService.createApplication(
                        "certLevelUpdate", false, ApplicationType.CERTIFICATE, null, "cert-level",
                        systemAdminAuthor, systemAdmin).join().content();
        assertThat(certificate.isActive()).isTrue();
        assertThat(certificate.isSystemAdmin()).isFalse();

        final ApplicationCertificate systemAdminCert =
                (ApplicationCertificate) applicationRegistryService.updateApplicationLevel(
                        ctx, "certLevelUpdate", new ApplicationLevelRequest("SYSTEMADMIN"),
                        systemAdminAuthor, systemAdmin).join();
        assertThat(systemAdminCert.isSystemAdmin()).isTrue();

        final ApplicationCertificate userCert =
                (ApplicationCertificate) applicationRegistryService.updateApplicationLevel(
                        ctx, "certLevelUpdate", new ApplicationLevelRequest("USER"),
                        systemAdminAuthor, systemAdmin).join();
        assertThat(userCert.isSystemAdmin()).isFalse();

        assertThatThrownBy(
                () -> applicationRegistryService.updateApplicationLevel(
                        ctx, "certLevelUpdate", new ApplicationLevelRequest("INVALID"),
                        systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTokenAndUpdateLevel() throws JsonParseException {
        assertThat(systemAdminClient.post(API_V1_PATH_PREFIX + "applications",
                                          QueryParams.of("appId", "forUpdate", "applicationType", "TOKEN",
                                                         "isSystemAdmin", false),
                                          HttpData.empty())
                                    .aggregate()
                                    .join()
                                    .headers()
                                    .get(HttpHeaderNames.LOCATION)).isEqualTo("/applications/forUpdate");

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH,
                                                         API_V1_PATH_PREFIX + "applications/forUpdate/level",
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);

        final String body = "{\"level\":\"SYSTEMADMIN\"}";
        final AggregatedHttpResponse response = systemAdminClient.execute(headers, body).aggregate().join();

        final JsonNode jsonNode = Jackson.readTree(response.contentUtf8());
        assertThat(jsonNode.get("appId").asText()).isEqualTo("forUpdate");
        assertThat(jsonNode.get("systemAdmin").asBoolean()).isEqualTo(true);

        final AggregatedHttpResponse response2 = systemAdminClient.execute(headers, body).aggregate().join();
        assertThat(response2.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }
}
