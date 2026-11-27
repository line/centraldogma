/*
 * Copyright 2026 LINE Corporation
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
package com.linecorp.centraldogma.xds.k8s.v1;

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialFile;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static com.linecorp.centraldogma.server.internal.storage.InternalProjectConstants.INTERNAL_PROJECT_XDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.credential.AccessTokenCredential;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.fabric8.kubernetes.client.OAuthTokenProvider;

class XdsKubernetesEndpointFetchingPluginTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void updatesProviderForRepositoryCredential() {
        assertCredentialUpdate(
                credentialName(INTERNAL_PROJECT_XDS, "my-group", "kube-credential"));
    }

    @Test
    void updatesProviderForProjectCredential() {
        assertCredentialUpdate(
                credentialName(INTERNAL_PROJECT_XDS, "kube-credential"));
    }

    @Test
    void clearsProviderWhenCredentialIsRemoved() {
        final XdsKubernetesEndpointFetchingPlugin plugin = new XdsKubernetesEndpointFetchingPlugin();
        final String credentialName = credentialName(INTERNAL_PROJECT_XDS, "my-group", "kube-credential");
        final OAuthTokenProvider provider = plugin.oauthTokenProvider(credentialName, "old-token");

        plugin.onCredentialsChanged(Map.of());

        assertThat(provider.getToken()).isEmpty();
    }

    @Test
    void restoresProviderWhenCredentialIsRecreated() {
        final XdsKubernetesEndpointFetchingPlugin plugin = new XdsKubernetesEndpointFetchingPlugin();
        final String credentialName = credentialName(INTERNAL_PROJECT_XDS, "my-group", "kube-credential");
        final OAuthTokenProvider provider = plugin.oauthTokenProvider(credentialName, "old-token");
        final String credentialPath = credentialFile(credentialName);

        plugin.onCredentialsChanged(Map.of());
        assertThat(provider.getToken()).isEmpty();

        final Entry<JsonNode> recreatedCredential = Entry.ofJson(
                Revision.INIT, credentialPath,
                Jackson.valueToTree(new AccessTokenCredential(credentialName, "recreated-token")));
        plugin.onCredentialsChanged(Map.of(credentialPath, recreatedCredential));

        assertThat(plugin.oauthTokenProvider(credentialName, "ignored-token")).isSameAs(provider);
        assertThat(provider.getToken()).isEqualTo("recreated-token");
    }

    @Test
    void updatesProviderWhenCredentialIsEditedInRepository() throws Exception {
        final MetaRepository metaRepository = dogma.projectManager().get(INTERNAL_PROJECT_XDS).metaRepo();
        final XdsKubernetesEndpointFetchingPlugin plugin = new XdsKubernetesEndpointFetchingPlugin();
        final String credentialName = credentialName(INTERNAL_PROJECT_XDS, "my-group", "kube-credential");
        final String credentialPath = credentialFile(credentialName);
        final OAuthTokenProvider provider = plugin.oauthTokenProvider(credentialName, "initial-token");
        plugin.registerCredentialListener(metaRepository);

        metaRepository.commit(Revision.HEAD, 0, Author.DEFAULT, "Add Kubernetes credential",
                              List.of(Change.ofJsonUpsert(credentialPath, Jackson.writeValueAsString(
                                      new AccessTokenCredential(credentialName, "old-token"))))).join();
        await().untilAsserted(() -> assertThat(provider.getToken()).isEqualTo("old-token"));

        metaRepository.commit(Revision.HEAD, 0, Author.DEFAULT, "Edit Kubernetes credential",
                              List.of(Change.ofJsonUpsert(credentialPath, Jackson.writeValueAsString(
                                      new AccessTokenCredential(credentialName, "new-token"))))).join();

        await().untilAsserted(() -> assertThat(provider.getToken()).isEqualTo("new-token"));
    }

    private static void assertCredentialUpdate(String credentialName) {
        final XdsKubernetesEndpointFetchingPlugin plugin = new XdsKubernetesEndpointFetchingPlugin();
        final OAuthTokenProvider provider = plugin.oauthTokenProvider(credentialName, "old-token");
        final String credentialPath = credentialFile(credentialName);
        final Entry<JsonNode> credentialEntry = Entry.ofJson(
                Revision.INIT, credentialPath,
                Jackson.valueToTree(new AccessTokenCredential(credentialName, "new-token")));

        plugin.onCredentialsChanged(Map.of(credentialPath, credentialEntry));

        assertThat(provider.getToken()).isEqualTo("new-token");
        assertThat(plugin.oauthTokenProvider(credentialName, "ignored-token")).isSameAs(provider);
        assertThat(provider.getToken()).isEqualTo("new-token");
    }
}
