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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

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

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.api.sysadmin.AppIdentityLevelRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.AppIdentityRegistryService;
import com.linecorp.centraldogma.server.metadata.AppIdentity;
import com.linecorp.centraldogma.server.metadata.AppIdentityRegistry;
import com.linecorp.centraldogma.server.metadata.AppIdentityType;
import com.linecorp.centraldogma.server.metadata.CertificateAppIdentity;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

import io.netty.util.internal.StringUtil;

class AppIdentityRegistryServiceTest {

    @RegisterExtension
    static final ProjectManagerExtension manager = new ProjectManagerExtension();

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

    private static AppIdentityRegistryService appIdentityRegistryService;
    private static MetadataService metadataService;

    // ctx is only used for getting the blocking task executor.
    private final ServiceRequestContext ctx =
            ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();

    @BeforeAll
    static void setUp() throws JsonMappingException, JsonParseException {
        metadataService = new MetadataService(manager.projectManager(), manager.executor(),
                                              manager.internalProjectInitializer());
        appIdentityRegistryService = new AppIdentityRegistryService(manager.executor(), metadataService,
                                                                    true);
    }

    @AfterEach
    public void tearDown() {
        final AppIdentityRegistry registry = metadataService.fetchAppIdentityRegistry().join();
        registry.appIds().forEach((appId, appIdentity) -> {
            if (!appIdentity.isDeleted()) {
                if (appIdentity.type() == AppIdentityType.TOKEN) {
                    metadataService.destroyToken(systemAdminAuthor, appId);
                } else {
                    metadataService.destroyCertificate(systemAdminAuthor, appId);
                }
            }
            metadataService.purgeAppIdentity(systemAdminAuthor, appId);
        });
    }

    @Test
    void systemAdminToken() {
        final Token token = appIdentityRegistryService.createToken("forAdmin1", true, null,
                                                                   systemAdminAuthor, systemAdmin).join()
                                                      .content();
        assertThat(token.isActive()).isTrue();
        assertThatThrownBy(
                () -> appIdentityRegistryService.createToken("forAdmin2", true, null, guestAuthor, guest)
                                                .join())
                .isInstanceOf(IllegalArgumentException.class);

        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) manager.executor();
        executor.execute(Command.createProject(Author.SYSTEM, "myPro")).join();
        metadataService.addAppIdentity(Author.SYSTEM, "myPro", "forAdmin1", ProjectRole.OWNER).join();
        await().untilAsserted(() -> assertThat(metadataService.getProject("myPro").join().appIds()
                                                              .containsKey("forAdmin1")).isTrue());

        final Collection<Token> tokens = appIdentityRegistryService.listTokens(systemAdmin);
        assertThat(tokens.stream().filter(t -> !StringUtil.isNullOrEmpty(t.secret()))).hasSize(1);

        assertThatThrownBy(() -> appIdentityRegistryService.deleteToken(ctx, "forAdmin1", guestAuthor, guest)
                                                           .join())
                .hasCauseInstanceOf(HttpResponseException.class);

        appIdentityRegistryService.deleteToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin).join();
        assertThat(appIdentityRegistryService.purgeToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin)
                                             .join())
                .satisfies(t -> {
                    assertThat(t.appId()).isEqualTo(token.appId());
                    assertThat(t.isSystemAdmin()).isEqualTo(token.isSystemAdmin());
                    assertThat(t.creation()).isEqualTo(token.creation());
                    assertThat(t.isDeleted()).isTrue();
                });
        await().untilAsserted(() -> assertThat(
                appIdentityRegistryService.listTokens(systemAdmin).size()).isEqualTo(0));
        assertThat(metadataService.getProject("myPro").join().appIds().size()).isEqualTo(0);
    }

    @Test
    void systemAdminAppIdentity() {
        final CertificateAppIdentity certificate =
                (CertificateAppIdentity) appIdentityRegistryService.createAppIdentity(
                        "certAdmin1", true, AppIdentityType.CERTIFICATE, null, "cert/123",
                        systemAdminAuthor, systemAdmin).join().content();
        assertThat(certificate.isActive()).isTrue();
        assertThat(certificate.certificateId()).isEqualTo("cert/123");
        assertThatThrownBy(
                () -> appIdentityRegistryService.createAppIdentity(
                        "certAdmin2", true,
                        AppIdentityType.CERTIFICATE, null,
                        "cert-456", guestAuthor, guest).join())
                .isInstanceOf(IllegalArgumentException.class);

        final Token token = (Token) appIdentityRegistryService.createAppIdentity(
                "tokenAdmin1", true, AppIdentityType.TOKEN, null, null,
                systemAdminAuthor, systemAdmin).join().content();
        assertThat(token.isActive()).isTrue();
        assertThat(token.secret()).isNotNull();

        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) manager.executor();
        executor.execute(Command.createProject(Author.SYSTEM, "certPro")).join();
        metadataService.addAppIdentity(Author.SYSTEM, "certPro", "certAdmin1", ProjectRole.OWNER).join();
        await().untilAsserted(() -> assertThat(metadataService.getProject("certPro").join().appIds()
                                                              .containsKey("certAdmin1")).isTrue());

        final AppIdentityRegistry appIdentityRegistry = metadataService.getAppIdentityRegistry();
        assertThat(appIdentityRegistry.appIds().keySet()).containsExactlyInAnyOrder(
                "certAdmin1", "tokenAdmin1");
        assertThat(appIdentityRegistry.secrets()).hasSize(1);
        appIdentityRegistry.secrets().values().forEach(
                tokenAppId -> assertThat(tokenAppId).isEqualTo("tokenAdmin1"));
        assertThat(appIdentityRegistry.certificateIds()).contains(Maps.immutableEntry(
                "cert/123", "certAdmin1"));

        final Collection<AppIdentity> appIdentities =
                appIdentityRegistryService.listAppIdentities(systemAdmin)
                                          .stream()
                                          .collect(toImmutableList());
        assertThat(appIdentities).contains(certificate, token);

        assertThatThrownBy(
                () -> appIdentityRegistryService.deleteAppIdentity(ctx, "certAdmin1", guestAuthor, guest)
                                                .join())
                .hasCauseInstanceOf(HttpResponseException.class);

        final CertificateAppIdentity deleted =
                (CertificateAppIdentity) appIdentityRegistryService.deleteAppIdentity(
                        ctx, "certAdmin1", systemAdminAuthor, systemAdmin).join();
        assertThat(deleted.appId()).isEqualTo(certificate.appId());

        assertThat(appIdentityRegistryService.purgeAppIdentity(ctx, "certAdmin1", systemAdminAuthor,
                                                               systemAdmin).join())
                .satisfies(c -> {
                    assertThat(c.appId()).isEqualTo(certificate.appId());
                    assertThat(c.isSystemAdmin()).isEqualTo(certificate.isSystemAdmin());
                    assertThat(c.creation()).isEqualTo(certificate.creation());
                    assertThat(c.isDeleted()).isTrue();
                });
        await().untilAsserted(() -> assertThat(
                appIdentityRegistryService.listAppIdentities(systemAdmin).stream()
                                          .filter(app -> app instanceof CertificateAppIdentity)
                                          .count()).isEqualTo(0));
        assertThat(metadataService.getProject("certPro").join().appIds().size()).isEqualTo(0);
    }

    @Test
    void userToken() {
        final Token userToken1 = appIdentityRegistryService.createToken("forUser1", false, null,
                                                                        systemAdminAuthor,
                                                                        systemAdmin)
                                                           .join().content();
        final Token userToken2 = appIdentityRegistryService.createToken("forUser2", false, null, guestAuthor,
                                                                        guest)
                                                           .join().content();
        assertThat(userToken1.isActive()).isTrue();
        assertThat(userToken2.isActive()).isTrue();

        final Collection<Token> tokens = appIdentityRegistryService.listTokens(guest);
        assertThat(tokens.stream().filter(token -> !StringUtil.isNullOrEmpty(token.secret())).count())
                .isEqualTo(0);

        assertThat(
                appIdentityRegistryService.deleteToken(ctx, "forUser1", systemAdminAuthor, systemAdmin)
                                          .thenCompose(unused -> appIdentityRegistryService.purgeToken(
                                                  ctx, "forUser1", systemAdminAuthor, systemAdmin)).join())
                .satisfies(t -> {
                    assertThat(t.appId()).isEqualTo(userToken1.appId());
                    assertThat(t.isSystemAdmin()).isEqualTo(userToken1.isSystemAdmin());
                    assertThat(t.creation()).isEqualTo(userToken1.creation());
                    assertThat(t.deactivation()).isEqualTo(userToken1.deactivation());
                });
        assertThat(
                appIdentityRegistryService.deleteToken(ctx, "forUser2", guestAuthor, guest)
                                          .thenCompose(unused -> appIdentityRegistryService.purgeToken(
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
        final CertificateAppIdentity userCert1 =
                (CertificateAppIdentity) appIdentityRegistryService.createAppIdentity(
                        "certUser1", false, AppIdentityType.CERTIFICATE, null, "cert-user1",
                        systemAdminAuthor, systemAdmin).join().content();
        final CertificateAppIdentity userCert2 =
                (CertificateAppIdentity) appIdentityRegistryService.createAppIdentity(
                        "certUser2", false, AppIdentityType.CERTIFICATE, null, "cert-user2",
                        guestAuthor, guest).join().content();
        assertThat(userCert1.isActive()).isTrue();
        assertThat(userCert2.isActive()).isTrue();
        assertThat(userCert1.certificateId()).isEqualTo("cert-user1");
        assertThat(userCert2.certificateId()).isEqualTo("cert-user2");

        final Collection<CertificateAppIdentity> certificates =
                appIdentityRegistryService.listAppIdentities(guest).stream()
                                          .filter(app -> app instanceof CertificateAppIdentity)
                                          .map(app -> (CertificateAppIdentity) app)
                                          .collect(toImmutableList());
        assertThat(certificates.size()).isEqualTo(2);

        assertThat(appIdentityRegistryService
                           .deleteAppIdentity(
                                   ctx, "certUser1", systemAdminAuthor, systemAdmin)
                           .thenCompose(unused -> appIdentityRegistryService.purgeAppIdentity(
                                   ctx, "certUser1", systemAdminAuthor, systemAdmin)).join())
                .satisfies(c -> {
                    assertThat(c.appId()).isEqualTo(userCert1.appId());
                    assertThat(c.isSystemAdmin()).isEqualTo(userCert1.isSystemAdmin());
                    assertThat(c.creation()).isEqualTo(userCert1.creation());
                });
        assertThat(appIdentityRegistryService
                           .deleteAppIdentity(ctx, "certUser2", guestAuthor, guest)
                           .thenCompose(unused -> appIdentityRegistryService.purgeAppIdentity(
                                   ctx, "certUser2", guestAuthor, guest)).join())
                .satisfies(c -> {
                    assertThat(c.appId()).isEqualTo(userCert2.appId());
                    assertThat(c.isSystemAdmin()).isEqualTo(userCert2.isSystemAdmin());
                    assertThat(c.creation()).isEqualTo(userCert2.creation());
                });
    }

    @Test
    void nonRandomToken() {
        final Token token = appIdentityRegistryService.createToken("forAdmin1", true, "appToken-secret",
                                                                   systemAdminAuthor,
                                                                   systemAdmin)
                                                      .join().content();
        assertThat(token.isActive()).isTrue();

        final Collection<Token> tokens = appIdentityRegistryService.listTokens(systemAdmin);
        assertThat(tokens.stream().filter(t -> !StringUtil.isNullOrEmpty(t.secret()))).hasSize(1);

        assertThatThrownBy(() -> appIdentityRegistryService.createToken("forUser1", true,
                                                                        "appToken-secret", guestAuthor, guest)
                                                           .join())
                .isInstanceOf(IllegalArgumentException.class);

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.DELETE, "/tokens/{appId}/removed"));
        appIdentityRegistryService.deleteToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin).join();
        appIdentityRegistryService.purgeToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin).join();
    }

    @Test
    public void updateToken() {
        final Token token = appIdentityRegistryService.createToken("forUpdate", true, null,
                                                                   systemAdminAuthor, systemAdmin).join()
                                                      .content();
        assertThat(token.isActive()).isTrue();

        appIdentityRegistryService.updateToken(ctx, "forUpdate", deactivation, systemAdminAuthor, systemAdmin)
                                  .join();
        await().untilAsserted(() -> assertThat(metadataService.findAppIdentity("forUpdate").isActive())
                .isFalse());

        appIdentityRegistryService.updateToken(ctx, "forUpdate", activation, systemAdminAuthor, systemAdmin)
                                  .join();
        await().untilAsserted(() -> assertThat(metadataService.findAppIdentity("forUpdate").isActive())
                .isTrue());

        assertThatThrownBy(
                () -> appIdentityRegistryService.updateToken(ctx, "forUpdate", Jackson.valueToTree(
                        ImmutableList.of(ImmutableMap.of())), systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);

        appIdentityRegistryService.deleteToken(ctx, "forUpdate", systemAdminAuthor, systemAdmin).join();
        await().untilAsserted(() -> assertThat(metadataService.findAppIdentity("forUpdate").isDeleted())
                .isTrue());
        assertThatThrownBy(
                () -> appIdentityRegistryService.updateToken(ctx, "forUpdate", activation,
                                                             systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void updateCertificate() {
        final CertificateAppIdentity certificate =
                (CertificateAppIdentity) appIdentityRegistryService.createAppIdentity(
                        "certUpdate", true, AppIdentityType.CERTIFICATE, null, "cert/update",
                        systemAdminAuthor, systemAdmin).join().content();
        assertThat(certificate.isActive()).isTrue();

        final JsonNode deactivation = Jackson.valueToTree(ImmutableMap.of("status", "inactive"));
        final JsonNode activation = Jackson.valueToTree(ImmutableMap.of("status", "active"));
        appIdentityRegistryService.updateAppIdentity(ctx, "certUpdate", deactivation,
                                                     systemAdminAuthor, systemAdmin).join();
        await().untilAsserted(() -> assertThat(
                metadataService.findAppIdentity("certUpdate").isActive()).isFalse());

        appIdentityRegistryService.updateAppIdentity(ctx, "certUpdate", activation,
                                                     systemAdminAuthor, systemAdmin).join();
        await().untilAsserted(() -> assertThat(
                metadataService.findAppIdentity("certUpdate").isActive()).isTrue());

        assertThatThrownBy(
                () -> appIdentityRegistryService.updateAppIdentity(ctx, "certUpdate", Jackson.valueToTree(
                        ImmutableList.of(ImmutableMap.of())), systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(IllegalArgumentException.class);

        appIdentityRegistryService.deleteAppIdentity(ctx, "certUpdate", systemAdminAuthor, systemAdmin).join();
        await().untilAsserted(() -> assertThat(
                metadataService.findAppIdentity("certUpdate").isDeleted()).isTrue());
        assertThatThrownBy(
                () -> appIdentityRegistryService.updateAppIdentity(ctx, "certUpdate", activation,
                                                                   systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateTokenLevel() {
        final Token token = appIdentityRegistryService.createToken("forUpdate", false, null,
                                                                   systemAdminAuthor, systemAdmin).join()
                                                      .content();
        assertThat(token.isActive()).isTrue();

        final Token userToken =
                appIdentityRegistryService.updateTokenLevel(
                                                  ctx, "forUpdate", new AppIdentityLevelRequest("SYSTEMADMIN"),
                                                  systemAdminAuthor, systemAdmin)
                                          .join();
        assertThat(userToken.isSystemAdmin()).isTrue();

        final Token adminToken =
                appIdentityRegistryService.updateTokenLevel(
                                                  ctx, "forUpdate", new AppIdentityLevelRequest("USER"),
                                                  systemAdminAuthor, systemAdmin)
                                          .join();
        assertThat(adminToken.isSystemAdmin()).isFalse();

        assertThatThrownBy(
                () -> appIdentityRegistryService.updateTokenLevel(
                        ctx, "forUpdate", new AppIdentityLevelRequest("INVALID"),
                        systemAdminAuthor, systemAdmin).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateCertificateLevel() {
        final CertificateAppIdentity certificate =
                (CertificateAppIdentity) appIdentityRegistryService.createAppIdentity(
                        "certLevelUpdate", false, AppIdentityType.CERTIFICATE, null, "cert-level",
                        systemAdminAuthor, systemAdmin).join().content();
        assertThat(certificate.isActive()).isTrue();
        assertThat(certificate.isSystemAdmin()).isFalse();

        final CertificateAppIdentity systemAdminCert =
                (CertificateAppIdentity) appIdentityRegistryService.updateAppIdentityLevel(
                        ctx, "certLevelUpdate", new AppIdentityLevelRequest("SYSTEMADMIN"),
                        systemAdminAuthor, systemAdmin).join();
        assertThat(systemAdminCert.isSystemAdmin()).isTrue();

        final CertificateAppIdentity userCert =
                (CertificateAppIdentity) appIdentityRegistryService.updateAppIdentityLevel(
                        ctx, "certLevelUpdate", new AppIdentityLevelRequest("USER"),
                        systemAdminAuthor, systemAdmin).join();
        assertThat(userCert.isSystemAdmin()).isFalse();

        assertThatThrownBy(
                () -> appIdentityRegistryService.updateAppIdentityLevel(
                        ctx, "certLevelUpdate", new AppIdentityLevelRequest("INVALID"),
                        systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
