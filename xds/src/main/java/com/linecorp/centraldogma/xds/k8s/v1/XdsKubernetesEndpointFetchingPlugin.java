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
package com.linecorp.centraldogma.xds.k8s.v1;

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialFile;
import static com.linecorp.centraldogma.server.internal.storage.InternalProjectConstants.INTERNAL_PROJECT_XDS;
import static io.fabric8.kubernetes.client.Config.KUBERNETES_DISABLE_AUTO_CONFIG_SYSTEM_PROPERTY;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.internal.credential.AccessTokenCredential;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryListener;

import io.fabric8.kubernetes.client.OAuthTokenProvider;

/**
 * A plugin that fetches Kubernetes endpoints from Central Dogma and provides them to the control plane.
 */
public final class XdsKubernetesEndpointFetchingPlugin implements Plugin {

    private static final Logger logger = LoggerFactory.getLogger(XdsKubernetesEndpointFetchingPlugin.class);
    private static final String CREDENTIALS_PATH_PATTERN =
            "/credentials/*.json,/repos/*/credentials/*.json";

    static {
        System.setProperty(KUBERNETES_DISABLE_AUTO_CONFIG_SYSTEM_PROPERTY, "true");
    }

    @Nullable
    private XdsKubernetesEndpointFetchingService fetchingService;

    private final Map<String, XdsKubernetesOAuthTokenProvider> oauthTokenProviders = new ConcurrentHashMap<>();

    private boolean credentialListenerRegistered;

    private final RepositoryListener credentialListener =
            RepositoryListener.of(CREDENTIALS_PATH_PATTERN, this::onCredentialsChanged);

    @Override
    public PluginTarget target(CentralDogmaConfig config) {
        return PluginTarget.LEADER_ONLY;
    }

    @Override
    public synchronized CompletionStage<Void> start(PluginContext context) {
        requireNonNull(context, "context");
        if (fetchingService != null) {
            return UnmodifiableFuture.completedFuture(null);
        }
        context.internalProjectInitializer().initialize(INTERNAL_PROJECT_XDS);

        final Project xdsProject = context.projectManager().get(INTERNAL_PROJECT_XDS);
        registerCredentialListener(xdsProject.metaRepo());

        fetchingService = new XdsKubernetesEndpointFetchingService(
                xdsProject, context.commandExecutor(), context.meterRegistry(), this::oauthTokenProvider);
        fetchingService.start();
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> stop(PluginContext context) {
        if (fetchingService == null) {
            return UnmodifiableFuture.completedFuture(null);
        }
        fetchingService.stop();
        fetchingService = null;
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public Class<?> configType() {
        return getClass();
    }

    OAuthTokenProvider oauthTokenProvider(String credentialName, String token) {
        return oauthTokenProviders.computeIfAbsent(
                credentialName, unused -> new XdsKubernetesOAuthTokenProvider(token));
    }

    synchronized void registerCredentialListener(MetaRepository metaRepository) {
        if (!credentialListenerRegistered) {
            // RepositoryListener has no removal API, so keep one listener for this plugin instance and
            // share its providers across all endpoint groups.
            metaRepository.addListener(credentialListener);
            credentialListenerRegistered = true;
        }
    }

    void onCredentialsChanged(Map<String, Entry<?>> entries) {
        oauthTokenProviders.forEach((credentialName, tokenProvider) -> {
            final Entry<?> entry = entries.get(credentialFile(credentialName));
            if (entry == null) {
                // The credential was removed. Stop sending its previous token to Kubernetes.
                tokenProvider.updateToken("");
                return;
            }

            try {
                final Credential credential = Jackson.treeToValue((JsonNode) entry.content(), Credential.class);
                if (credential instanceof AccessTokenCredential) {
                    tokenProvider.updateToken(((AccessTokenCredential) credential).accessToken());
                } else {
                    logger.warn("The Kubernetes credential is no longer an access token: {}",
                                credentialName);
                    tokenProvider.updateToken("");
                }
            } catch (Exception e) {
                logger.warn("Failed to refresh the Kubernetes credential: {}", credentialName, e);
                tokenProvider.updateToken("");
            }
        });
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("target", PluginTarget.LEADER_ONLY)
                          .add("configType", configType().getName())
                          .toString();
    }
}
