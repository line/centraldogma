/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.centraldogma.it.mirror.git;

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.BAR_REPO;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.FOO_PROJ;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.PRIVATE_KEY_FILE;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.TEST_MIRROR_ID;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.getCreateCredentialRequest;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.InvalidHttpResponseException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.MirrorException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.ZoneConfig;
import com.linecorp.centraldogma.server.credential.CreateCredentialRequest;
import com.linecorp.centraldogma.server.internal.storage.repository.MirrorConfig;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;
import com.linecorp.centraldogma.testing.internal.CentralDogmaRuleDelegate;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;

class ZoneAwareMirrorTest {

    private static final List<String> ZONES = ImmutableList.of("zone1", "zone2", "zone3");

    @RegisterExtension
    CentralDogmaReplicationExtension cluster = new CentralDogmaReplicationExtension(3) {
        @Override
        protected void configureEach(int serverId, CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
            builder.zone(new ZoneConfig(ZONES.get(serverId - 1), ZONES));
            builder.pluginConfigs(new MirroringServicePluginConfig(true, null, null, null, true, false));
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private static int serverPort;
    private static String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        final CentralDogmaRuleDelegate server1 = cluster.servers().get(0);
        serverPort = server1.serverAddress().getPort();
        accessToken = getAccessToken(
                WebClient.of("http://127.0.0.1:" + serverPort),
                USERNAME, PASSWORD);

        final CentralDogma client =
                new ArmeriaCentralDogmaBuilder()
                        .host("127.0.0.1", serverPort)
                        .accessToken(accessToken)
                        .build();
        client.createProject(FOO_PROJ).join();
        for (String zone : ZONES) {
            client.createRepository(FOO_PROJ, BAR_REPO + '-' + zone).join();
        }
        client.createRepository(FOO_PROJ, "bar-default").join();
        client.createRepository(FOO_PROJ, "bar-unknown-zone").join();
        TestZoneAwareMirrorListener.reset();
    }

    @FieldSource("ZONES")
    @ParameterizedTest
    void shouldRunMirrorTaskOnPinnedZone(String zone) throws Exception {
        createMirror(zone);

        await().untilAsserted(() -> {
            // Wait for three mirror tasks to run to ensure that all tasks are running in the same zone.
            final AtomicInteger atomicInteger = TestZoneAwareMirrorListener.startCount.get(zone);
            assertThat(atomicInteger).isNotNull();
            assertThat(atomicInteger.get()).isGreaterThanOrEqualTo(3);
        });
        await().untilAsserted(() -> {
            final List<MirrorResult> results = TestZoneAwareMirrorListener.completions.get(zone);
            assertThat(results).hasSizeGreaterThan(3);
            // Make sure that the mirror was executed in the specified zone.
            assertThat(results).allSatisfy(result -> {
                assertThat(result.zone()).isEqualTo(zone);
                assertThat(result.repoName()).isEqualTo(BAR_REPO + '-' + zone);
            });
        });
        assertThat(TestZoneAwareMirrorListener.errors.get(zone)).isNullOrEmpty();
    }

    @Test
    void shouldRunUnpinnedMirrorTaskOnDefaultZone() throws Exception {
        createMirror(null);
        // The default zone is the first zone in the list.
        final String defaultZone = ZONES.get(0);
        await().untilAsserted(() -> {
            // Wait for 3 mirror tasks to be run to verify all jobs are executed in the same zone.
            final AtomicInteger atomicInteger = TestZoneAwareMirrorListener.startCount.get(defaultZone);
            assertThat(atomicInteger).isNotNull();
            assertThat(atomicInteger.get()).isGreaterThanOrEqualTo(3);
        });
        await().untilAsserted(() -> {
            final List<MirrorResult> results = TestZoneAwareMirrorListener.completions.get(defaultZone);
            assertThat(results).hasSizeGreaterThan(3);
            // Make sure that the mirror was executed in the specified zone.
            assertThat(results).allSatisfy(mirrorResult -> {
                assertThat(mirrorResult.zone()).isNull();
                assertThat(mirrorResult.repoName()).isEqualTo("bar-default");
            });
        });
    }

    @Test
    void shouldRejectUnknownZone() throws Exception {
        final String unknownZone = "unknown-zone";
        final InvalidHttpResponseException invalidResponseException =
                catchThrowableOfType(InvalidHttpResponseException.class, () -> createMirror(unknownZone));
        assertThat(invalidResponseException.response().status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalidResponseException.response().contentUtf8())
                .contains("The zone 'unknown-zone' is not in the zone configuration");
    }

    @Test
    void shouldWarnUnknownZoneForScheduledJob() throws Exception {
        final CentralDogma client = cluster.servers().get(0).client();
        final CentralDogmaRepository repo = client.forRepo(FOO_PROJ, "meta");
        final String mirrorId = TEST_MIRROR_ID + "-unknown-zone";
        final String unknownZone = "unknown-zone";
        final MirrorConfig mirrorConfig =
                new MirrorConfig(mirrorId,
                                 true,
                                 "0/1 * * * * ?",
                                 MirrorDirection.REMOTE_TO_LOCAL,
                                 "bar-unknown-zone",
                                 "/",
                                 URI.create("git+ssh://github.com/line/centraldogma-authtest.git/#main"),
                                 null,
                                 null,
                                 credentialName("foo", "bar-unknown-zone", "credential-id"),
                                 unknownZone);
        final Change<JsonNode> change = Change.ofJsonUpsert(
                "/repos/bar-unknown-zone/mirrors/" + mirrorId + ".json",
                Jackson.writeValueAsString(mirrorConfig));
        repo.commit("Add a mirror having an invalid zone", change)
            .push().join();

        await().untilAsserted(() -> {
            // Wait for 3 mirror tasks to be run to verify all jobs are executed in the same zone.
            final AtomicInteger atomicInteger = TestZoneAwareMirrorListener.startCount.get(unknownZone);
            assertThat(atomicInteger).isNotNull();
            assertThat(atomicInteger.get()).isGreaterThanOrEqualTo(1);
        });
        await().untilAsserted(() -> {
            final List<MirrorResult> results = TestZoneAwareMirrorListener.completions.get(unknownZone);
            assertThat(results).isNullOrEmpty();
            final List<Throwable> causes = TestZoneAwareMirrorListener.errors.get(unknownZone);

            // Make sure that the mirror was executed in the specified zone.
            assertThat(causes).allSatisfy(cause -> {
                assertThat(cause).isInstanceOf(MirrorException.class)
                                 .hasMessage("The mirror is pinned to an unknown zone: unknown-zone " +
                                             "(valid zones: " + ZONES + ')');
            });
        });
    }

    private static void createMirror(@Nullable String zone) throws Exception {
        final BlockingWebClient client = WebClient.builder("http://127.0.0.1:" + serverPort)
                                                  .auth(AuthToken.ofOAuth2(accessToken))
                                                  .build()
                                                  .blocking();

        final CreateCredentialRequest credential = getCreateCredentialRequest(FOO_PROJ, null);
        ResponseEntity<PushResultDto> response =
                client.prepare()
                      .post("/api/v1/projects/{proj}/credentials")
                      .pathParam("proj", FOO_PROJ)
                      .contentJson(credential)
                      .asJson(PushResultDto.class)
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        final MirrorRequest newMirror = newMirror(zone);
        response = client.prepare()
                         .post("/api/v1/projects/{proj}/repos/{repo}/mirrors")
                         .pathParam("proj", FOO_PROJ)
                         .pathParam("repo", BAR_REPO + '-' + (zone == null ? "default" : zone))
                         .contentJson(newMirror)
                         .asJson(PushResultDto.class)
                         .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    }

    private static MirrorRequest newMirror(@Nullable String zone) {
        return new MirrorRequest(TEST_MIRROR_ID + '-' + (zone == null ? "default" : zone),
                                 true,
                                 FOO_PROJ,
                                 "0/1 * * * * ?",
                                 "REMOTE_TO_LOCAL",
                                 BAR_REPO + '-' + (zone == null ? "default" : zone),
                                 "/",
                                 "git+ssh",
                                 "github.com/line/centraldogma-authtest.git",
                                 "/",
                                 "main",
                                 null,
                                 credentialName(FOO_PROJ, PRIVATE_KEY_FILE),
                                 zone);
    }
}
