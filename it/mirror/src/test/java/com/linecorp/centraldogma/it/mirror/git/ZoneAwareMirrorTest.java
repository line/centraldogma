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

import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.BAR_REPO;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.FOO_PROJ;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.PRIVATE_KEY_FILE;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.TEST_MIRROR_ID;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.getCredential;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.ZoneConfig;
import com.linecorp.centraldogma.server.internal.credential.PublicKeyCredential;
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
            builder.administrators(USERNAME);
            builder.zone(new ZoneConfig(ZONES.get(serverId - 1), ZONES));
            builder.pluginConfigs(new MirroringServicePluginConfig(true, null, null, null, true));
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
            assertThat(TestZoneAwareMirrorListener.startCount.get(zone)).isGreaterThanOrEqualTo(3);
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
        assertThat(TestZoneAwareMirrorListener.errors.get(zone)).isEmpty();
    }

    @Test
    void shouldRunUnpinnedMirrorTaskOnDefaultZone() throws Exception {
        createMirror(null);
        // The default zone is the first zone in the list.
        final String defaultZone = ZONES.get(0);
        await().untilAsserted(() -> {
            // Wait for 3 mirror tasks to be run to verify all jobs are executed in the same zone.
            assertThat(TestZoneAwareMirrorListener.startCount.get(defaultZone)).isGreaterThanOrEqualTo(3);
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
    void shouldNotRunMirrorTaskForUnknownZone() throws Exception {
        final String unknownZone = "unknown-zone";
        createMirror(unknownZone);

        // Wait for 3 seconds to ensure that the mirror task is not executed.
        Thread.sleep(3000);

        await().untilAsserted(() -> {
            assertThat(TestZoneAwareMirrorListener.startCount).isEmpty();
        });
        await().untilAsserted(() -> {
            assertThat(TestZoneAwareMirrorListener.completions).isEmpty();
            assertThat(TestZoneAwareMirrorListener.errors).isEmpty();
        });
    }

    private static List<String> testZone() {
        final ArrayList<String> zones = new ArrayList<>(ZONES);
        // Add a null zone to test the default zone.
        zones.add(null);
        return zones;
    }

    private static void createMirror(String zone) throws Exception {
        final BlockingWebClient client = WebClient.builder("http://127.0.0.1:" + serverPort)
                                                  .auth(AuthToken.ofOAuth2(accessToken))
                                                  .build()
                                                  .blocking();

        final PublicKeyCredential credential = getCredential();
        ResponseEntity<PushResultDto> response =
                client.prepare()
                      .post("/api/v1/projects/{proj}/credentials")
                      .pathParam("proj", FOO_PROJ)
                      .contentJson(credential)
                      .asJson(PushResultDto.class)
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        final MirrorDto newMirror = newMirror(zone);
        response = client.prepare()
                         .post("/api/v1/projects/{proj}/mirrors")
                         .pathParam("proj", FOO_PROJ)
                         .contentJson(newMirror)
                         .asJson(PushResultDto.class)
                         .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    }

    private static MirrorDto newMirror(@Nullable String zone) {
        return new MirrorDto(TEST_MIRROR_ID + '-' + (zone == null ? "default" : zone),
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
                             PRIVATE_KEY_FILE,
                             zone);
    }
}
