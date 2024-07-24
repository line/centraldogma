/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationService.PATH_LEGACY_CREDENTIALS;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationService.PATH_LEGACY_CREDENTIALS_BACKUP;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationService.PATH_LEGACY_MIRRORS;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationService.PATH_LEGACY_MIRRORS_BACKUP;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.ACCESS_TOKEN_CREDENTIAL;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.PASSWORD_CREDENTIAL;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.PUBLIC_KEY_CREDENTIAL;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.REPO0_MIRROR;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.REPO1_MIRROR;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.REPO2_MIRROR;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.REPO3_MIRROR;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.TEST_PROJ;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.TEST_REPO0;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.TEST_REPO1;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.TEST_REPO2;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.TEST_REPO3;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.assertCredential;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationServiceTest.assertMirrorConfig;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.util.PortUtil;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.PluginConfig;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.server.ZooKeeperServerConfig;
import com.linecorp.centraldogma.server.auth.AuthProviderFactory;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;

class MirroringMigrationServiceClusterTest {

    @RegisterExtension
    static final TemporaryFolderExtension tempDir = new TemporaryFolderExtension();
    private final AuthProviderFactory factory = new TestAuthProviderFactory();

    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @Test
    void shouldMigrateMirrorConfigsWithZooKeeper() throws Exception {
        final int numberReplicas = 3;
        final int[] ports = unusedTcpPorts(3 * numberReplicas);
        final Map<Integer, ZooKeeperServerConfig> serverConfigs = new HashMap<>();
        for (int i = 0; i < numberReplicas; i++) {
            final int quorumPort = ports[i * 3];
            final int electionPort = ports[i * 3 + 1];
            serverConfigs.put(i + 1, new ZooKeeperServerConfig("127.0.0.1", quorumPort, electionPort, 0,
                                                               null, null));
        }
        final List<CentralDogma> servers =
                IntStream.range(0, numberReplicas).mapToObj(i -> {
                             try {
                                 final File data = tempDir.newFolder().toFile();
                                 final ZooKeeperReplicationConfig replicationConfig =
                                         new ZooKeeperReplicationConfig(i + 1, serverConfigs);
                                 final int port = ports[i * 3 + 2];
                                 return new CentralDogmaBuilder(data)
                                         .port(port, SessionProtocol.HTTP)
                                         .pluginConfigs(new PluginConfig("mirror", false, null))
                                         .authProviderFactory(factory)
                                         .replication(replicationConfig)
                                         .administrators(TestAuthMessageUtil.USERNAME)
                                         .build();
                             } catch (IOException e) {
                                 throw new RuntimeException(e);
                             }
                         })
                         .collect(toImmutableList());
        final List<CompletableFuture<Void>> futures = servers.stream().map(CentralDogma::start)
                                                             .collect(toImmutableList());
        CompletableFutures.allAsList(futures).join();

        final int serverPort = servers.get(0).activePort().localAddress().getPort();
        final String accessToken = getAccessToken(WebClient.of("http://127.0.0.1:" + serverPort),
                                                  TestAuthMessageUtil.USERNAME,
                                                  TestAuthMessageUtil.PASSWORD);
        final com.linecorp.centraldogma.client.CentralDogma client =
                new ArmeriaCentralDogmaBuilder()
                        .host("127.0.0.1", serverPort)
                        .accessToken(accessToken)
                        .build();

        client.createProject(TEST_PROJ).join();
        client.createRepository(TEST_PROJ, TEST_REPO0).join();
        client.createRepository(TEST_PROJ, TEST_REPO1).join();
        client.createRepository(TEST_PROJ, TEST_REPO2).join();
        client.createRepository(TEST_PROJ, TEST_REPO3).join();

        final String mirrorsJson =
                '[' + REPO0_MIRROR + ',' + REPO1_MIRROR + ',' + REPO2_MIRROR + ',' + REPO3_MIRROR + ']';
        client.push(TEST_PROJ, Project.REPO_META, Revision.HEAD, "Create a new mirrors.json", "",
                    Markup.PLAINTEXT, Change.ofJsonUpsert(PATH_LEGACY_MIRRORS, mirrorsJson)).join();

        final String credentialJson = '[' + PUBLIC_KEY_CREDENTIAL + ',' + PASSWORD_CREDENTIAL + ',' +
                                      ACCESS_TOKEN_CREDENTIAL + ']';
        client.push(TEST_PROJ, Project.REPO_META, Revision.HEAD, "Create a new credentials.json", "",
                    Markup.PLAINTEXT, Change.ofJsonUpsert(PATH_LEGACY_CREDENTIALS, credentialJson)).join();
        // Wait for the replication to complete.
        Thread.sleep(5000);

        final List<CentralDogma> newServers =
                servers.stream().parallel().map(server -> {
                           final int port = server.activePort().localAddress().getPort();
                           // Restart the servers with mirroring enabled.
                           server.stop().join();
                           return new CentralDogmaBuilder(server.config().dataDir())
                                   .port(port, SessionProtocol.HTTP)
                                   .replication(server.config().replicationConfig())
                                   .build();
                       })
                       .collect(toImmutableList());

        final List<CompletableFuture<Void>> newFutures = newServers.stream().map(CentralDogma::start)
                                                                   .collect(toImmutableList());
        CompletableFutures.allAsList(newFutures).join();
        // Wait for the mirroring migration to complete.
        Thread.sleep(60000);

        // Check if the mirroring migration is correctly completed.
        final int newServerPort = newServers.get(0).activePort().localAddress().getPort();
        final com.linecorp.centraldogma.client.CentralDogma newClient =
                new ArmeriaCentralDogmaBuilder()
                        .host("127.0.0.1", newServerPort)
                        .build();
        final CentralDogmaRepository repo = newClient.forRepo(TEST_PROJ, Project.REPO_META);

        final Map<String, Entry<?>> mirrorEntries = repo.file(PathPattern.of("/mirrors/*.json")).get().join();
        assertThat(mirrorEntries).hasSize(4);
        final Map<String, Map.Entry<String, Entry<?>>> mirrors =
                mirrorEntries.entrySet().stream()
                             .collect(toImmutableMap(e -> {
                                 try {
                                     return e.getValue().contentAsJson().get("localRepo").asText();
                                 } catch (JsonParseException ex) {
                                     throw new RuntimeException(ex);
                                 }
                             }, Function.identity()));

        assertMirrorConfig(mirrors.get(TEST_REPO0), "mirror-" + TEST_PROJ + '-' + TEST_REPO0,
                           REPO0_MIRROR);
        assertMirrorConfig(mirrors.get(TEST_REPO1), "mirror-1", REPO1_MIRROR);
        // "-khaki" suffix is added because the mirror ID is duplicated.
        assertMirrorConfig(mirrors.get(TEST_REPO2), "mirror-1-khaki", REPO2_MIRROR);
        // "-speakers" suffix is added because the mirror ID is duplicated.
        assertMirrorConfig(mirrors.get(TEST_REPO3), "mirror-1-speakers", REPO3_MIRROR);

        final Map<String, Entry<?>> credentialEntries = repo.file(PathPattern.of("/credentials/*.json"))
                                                            .get()
                                                            .join();

        assertThat(credentialEntries).hasSize(3);
        final Map<String, Map.Entry<String, Entry<?>>> credentials =
                credentialEntries.entrySet().stream()
                                 .collect(toImmutableMap(e -> {
                                     try {
                                         return e.getValue().contentAsJson().get("type").asText();
                                     } catch (JsonParseException ex) {
                                         throw new RuntimeException(ex);
                                     }
                                 }, Function.identity()));

        assertCredential(credentials.get("public_key"), "credential-" + TEST_PROJ + "-public_key",
                         PUBLIC_KEY_CREDENTIAL);
        assertCredential(credentials.get("password"), "credential-1", PASSWORD_CREDENTIAL);
        // "-1" suffix is added because the credential ID is duplicated.
        assertCredential(credentials.get("access_token"), "credential-1-slingshot", ACCESS_TOKEN_CREDENTIAL);

        // Make sure that the legacy files are renamed.
        assertThatThrownBy(() -> repo.file(PATH_LEGACY_MIRRORS).get().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(EntryNotFoundException.class);
        assertThatThrownBy(() -> repo.file(PATH_LEGACY_CREDENTIALS).get().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(EntryNotFoundException.class);
        assertThat(repo.file(PATH_LEGACY_MIRRORS_BACKUP).get().join()
                       .hasContent()).isTrue();
        assertThat(repo.file(PATH_LEGACY_CREDENTIALS_BACKUP).get().join()
                       .hasContent()).isTrue();
    }

    // Forked from ZooKeeperTestUtil in Armeria.
    private static int[] unusedTcpPorts(int numPorts) {
        final int[] ports = new int[numPorts];
        for (int i = 0; i < numPorts; i++) {
            int mayUnusedTcpPort;
            for (;;) {
                mayUnusedTcpPort = PortUtil.unusedTcpPort();
                if (i == 0) {
                    // The first acquired port is always unique.
                    break;
                }
                boolean isAcquiredPort = false;
                for (int j = 0; j < i; j++) {
                    isAcquiredPort = ports[j] == mayUnusedTcpPort;
                    if (isAcquiredPort) {
                        break;
                    }
                }

                if (isAcquiredPort) {
                    // Duplicate port. Look up an unused port again.
                    continue;
                } else {
                    // A newly acquired unique port.
                    break;
                }
            }
            ports[i] = mayUnusedTcpPort;
        }
        return ports;
    }
}
