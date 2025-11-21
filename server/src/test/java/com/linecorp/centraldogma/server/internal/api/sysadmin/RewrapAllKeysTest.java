/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.api.sysadmin;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.server.internal.api.sysadmin.KeyManagementServiceTest.createEncryptedRepository;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.EncryptionAtRestConfig;
import com.linecorp.centraldogma.server.internal.api.sysadmin.KeyManagementServiceTest.WrappedDek;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;

class RewrapAllKeysTest {

    @TempDir
    static File dataDir;

    @Test
    void rewrapAllKeys() throws Exception {
        // Step 1: Start server with kekId="oldKekId"
        final com.linecorp.centraldogma.server.CentralDogma server1 = startServer("oldKekId");
        final ServerContext ctx1 = createServerContext(server1, "testAppId1");

        try (CentralDogma client = createClient(ctx1)) {
            // Create project and encrypted repository
            client.createProject("foo").join();
            createEncryptedRepository(ctx1.webClient, "encryptedRepo");

            // Add some data with oldKekId (WDEK version 1)
            final PushResult push1 = pushData(client, "/data.json", "{ \"kek\": \"oldKekId\" }");
            assertThat(push1.revision().major()).isEqualTo(2);

            // Rotate WDEK to create version 2
            final AggregatedHttpResponse rotateResponse = rotateWdek(ctx1, "foo", "encryptedRepo");
            assertThat(rotateResponse.status()).isSameAs(HttpStatus.NO_CONTENT);

            // Add more data with the new WDEK version 2
            final PushResult push2 = pushData(client, "/data2.json", "{ \"version\": \"2\" }");
            assertThat(push2.revision().major()).isEqualTo(3);

            // Verify we now have 2 WDEK versions, both with oldKekId
            final List<WrappedDekDetails> wdeksAfterRotate = getWdeks(ctx1);
            // dogma, encryptedRepo 1, and encryptedRepo 2
            assertThat(wdeksAfterRotate).hasSize(3);
            assertThat(wdeksAfterRotate.stream().map(WrappedDek::of)).containsExactlyInAnyOrder(
                    new WrappedDek("foo", "encryptedRepo", 1, "oldKekId"),
                    new WrappedDek("foo", "encryptedRepo", 2, "oldKekId"),
                    new WrappedDek("foo", "dogma", 1, "oldKekId"));
            verifyKekId(wdeksAfterRotate, "oldKekId");
            verifyWrappedDekPrefix(wdeksAfterRotate, "oldKekId-wrapped-");

            // Verify session master key has oldKekId
            final SessionMasterKeyDto sessionKey1 = getSessionMasterKey(ctx1);
            assertThat(sessionKey1.kekId()).isEqualTo("oldKekId");

            // Verify we can read both data files
            assertThat(readFile(client, "/data.json")).contains("oldKekId");
            assertThat(readFile(client, "/data2.json")).contains("version");
        }
        server1.stop().join();

        // Step 2: Restart server with kekId="newKekId"
        final com.linecorp.centraldogma.server.CentralDogma server2 = startServer("newKekId");
        final ServerContext ctx2 = createServerContext(server2, "testAppId2");

        try (CentralDogma client = createClient(ctx2)) {
            final List<WrappedDekDetails> beforeRewrap = getWdeks(ctx2);
            assertThat(beforeRewrap).hasSize(3);
            assertThat(beforeRewrap.stream().map(WrappedDek::of)).containsExactlyInAnyOrder(
                    new WrappedDek("foo", "encryptedRepo", 1, "oldKekId"),
                    new WrappedDek("foo", "encryptedRepo", 2, "oldKekId"),
                    new WrappedDek("foo", "dogma", 1, "oldKekId"));
            verifyKekId(beforeRewrap, "oldKekId");

            // Call rewrap API
            final AggregatedHttpResponse rewrapResponse = callRewrapApi(ctx2);
            assertThat(rewrapResponse.status()).isSameAs(HttpStatus.NO_CONTENT);

            // Verify ALL WDEK versions (both v1 and v2) now have newKekId after rewrap
            final List<WrappedDekDetails> afterRewrap = getWdeks(ctx2);
            assertThat(afterRewrap).hasSameSizeAs(beforeRewrap);
            assertThat(afterRewrap).hasSize(3);
            assertThat(afterRewrap.stream().map(WrappedDek::of)).containsExactlyInAnyOrder(
                    new WrappedDek("foo", "encryptedRepo", 1, "newKekId"),
                    new WrappedDek("foo", "encryptedRepo", 2, "newKekId"),
                    new WrappedDek("foo", "dogma", 1, "newKekId"));
            verifyKekId(afterRewrap, "newKekId");
            verifyWrappedDekPrefix(afterRewrap, "newKekId-wrapped-");

            // Verify session master key has newKekId
            final SessionMasterKeyDto sessionKey2 = getSessionMasterKey(ctx2);
            assertThat(sessionKey2.kekId()).isEqualTo("newKekId");

            // Verify we can still read data encrypted with both old WDEK versions
            assertThat(readFile(client, "/data.json")).contains("oldKekId");
            assertThat(readFile(client, "/data2.json")).contains("version");

            // Verify we can write new data with the current WDEK (v2 with newKekId)
            final PushResult push3 = pushData(client, "/data3.json", "{ \"kek\": \"newKekId\" }");
            assertThat(push3.revision().major()).isEqualTo(4);
            assertThat(readFile(client, "/data3.json")).contains("newKekId");
        } finally {
            server2.stop().join();
        }
    }

    private static com.linecorp.centraldogma.server.CentralDogma startServer(String kekId) {
        final com.linecorp.centraldogma.server.CentralDogma server = new CentralDogmaBuilder(dataDir)
                .port(0, SessionProtocol.HTTP)
                .systemAdministrators(USERNAME)
                .authProviderFactory(new TestAuthProviderFactory())
                .encryptionAtRest(new EncryptionAtRestConfig(true, true, kekId))
                .build();
        server.start().join();
        return server;
    }

    private static ServerContext createServerContext(com.linecorp.centraldogma.server.CentralDogma server,
                                                     String appId) {
        final int port = server.activePort().localAddress().getPort();
        final String accessToken = getAccessToken(WebClient.of("http://127.0.0.1:" + port), USERNAME, PASSWORD, appId,
                                                  true, false, true);
        return new ServerContext(port, WebClient.builder("http://127.0.0.1:" + port)
                                                .auth(AuthToken.ofOAuth2(accessToken))
                                                .build(),
                                 accessToken);
    }

    private static CentralDogma createClient(ServerContext ctx) throws Exception {
        return new ArmeriaCentralDogmaBuilder()
                .host("127.0.0.1", ctx.port)
                .accessToken(ctx.accessToken)
                .build();
    }

    private static List<WrappedDekDetails> getWdeks(ServerContext ctx) {
        return ctx.webClient.blocking().prepare()
                  .get(API_V1_PATH_PREFIX + "/wdeks")
                  .header("Authorization", "Bearer " + ctx.accessToken)
                  .asJson(new TypeReference<List<WrappedDekDetails>>() {})
                  .execute()
                  .content();
    }

    private static SessionMasterKeyDto getSessionMasterKey(ServerContext ctx) {
        return ctx.webClient.blocking().prepare()
                  .get(API_V1_PATH_PREFIX + "/masterkeys/session")
                  .header("Authorization", "Bearer " + ctx.accessToken)
                  .asJson(SessionMasterKeyDto.class)
                  .execute()
                  .content();
    }

    private static AggregatedHttpResponse callRewrapApi(ServerContext ctx) {
        return ctx.webClient.execute(RequestHeaders.builder(HttpMethod.POST,
                                                            API_V1_PATH_PREFIX + "/keys/rewrap")
                                                   .set("Authorization", "Bearer " + ctx.accessToken)
                                                   .build())
                  .aggregate().join();
    }

    private static AggregatedHttpResponse rotateWdek(ServerContext ctx, String projectName, String repoName) {
        return ctx.webClient.execute(RequestHeaders.builder(HttpMethod.POST,
                                                            API_V1_PATH_PREFIX + "/projects/" + projectName +
                                                            "/repos/" + repoName + "/wdeks/rotate")
                                                   .set("Authorization", "Bearer " + ctx.accessToken)
                                                   .build())
                  .aggregate().join();
    }

    private static PushResult pushData(CentralDogma client, String path, String content) {
        return client.forRepo("foo", "encryptedRepo")
                    .commit("Add " + path, Change.ofJsonUpsert(path, content))
                    .push().join();
    }

    private static String readFile(CentralDogma client, String path) {
        return client.forRepo("foo", "encryptedRepo")
                    .file(path)
                    .get().join()
                    .contentAsText();
    }

    private static void verifyKekId(List<WrappedDekDetails> wdeks, String expectedKekId) {
        assertThat(wdeks).allMatch(w -> expectedKekId.equals(w.kekId()));
    }

    private static void verifyWrappedDekPrefix(List<WrappedDekDetails> wdeks, String expectedPrefix) {
        assertThat(wdeks).allMatch(w -> {
            final byte[] decoded = Base64.getDecoder().decode(w.wrappedDek());
            final String decodedStr = new String(decoded);
            return decodedStr.startsWith(expectedPrefix);
        });
    }

    private static final class ServerContext {
        final int port;
        final WebClient webClient;
        final String accessToken;

        ServerContext(int port, WebClient webClient, String accessToken) {
            this.port = port;
            this.webClient = webClient;
            this.accessToken = accessToken;
        }
    }
}

