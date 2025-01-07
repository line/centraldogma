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

package com.linecorp.centraldogma.server;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.common.util.InetAddressPredicates.ofCidr;
import static com.linecorp.armeria.common.util.InetAddressPredicates.ofExact;
import static com.linecorp.armeria.server.ClientAddressSource.ofHeader;
import static com.linecorp.armeria.server.ClientAddressSource.ofProxyProtocol;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.server.ClientAddressSource;
import com.linecorp.centraldogma.server.plugin.PluginConfig;

/**
 * abstract {@link CentralDogma} server configuration.
 */
public abstract class AbstractCentralDogmaConfig implements CentralDogmaConfigSpec {
    private final List<PluginConfig> pluginConfigs;
    private final Map<Class<? extends PluginConfig>, PluginConfig> pluginConfigMap;

    protected AbstractCentralDogmaConfig(@Nullable final List<PluginConfig> pluginConfigs) {
        this.pluginConfigs = firstNonNull(pluginConfigs, ImmutableList.of());
        pluginConfigMap = this.pluginConfigs.stream().collect(
                toImmutableMap(PluginConfig::getClass, Function.identity()));
    }

    @Override
    public List<PluginConfig> pluginConfigs() {
        return pluginConfigs;
    }

    @Override
    public Map<Class<? extends PluginConfig>, PluginConfig> pluginConfigMap() {
        return pluginConfigMap;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T extends PluginConfig> T pluginConfig(Class<T> pluginClass) {
        return (T) pluginConfigMap.entrySet()
                                  .stream()
                                  .filter(entry -> pluginClass.isAssignableFrom(entry.getKey()))
                                  .map(Entry::getValue)
                                  .findFirst()
                                  .orElse(null);
    }

    protected static Predicate<InetAddress> toTrustedProxyAddressPredicate(List<String> trustedProxyAddresses) {
        final String first = trustedProxyAddresses.get(0);
        Predicate<InetAddress> predicate = first.indexOf('/') < 0 ? ofExact(first) : ofCidr(first);
        for (int i = 1; i < trustedProxyAddresses.size(); i++) {
            final String next = trustedProxyAddresses.get(i);
            predicate = predicate.or(next.indexOf('/') < 0 ? ofExact(next) : ofCidr(next));
        }
        return predicate;
    }

    protected static List<ClientAddressSource> toClientAddressSourceList(
            @Nullable List<String> clientAddressSources,
            boolean useDefaultSources, boolean specifiedProxyProtocol) {
        if (clientAddressSources != null && !clientAddressSources.isEmpty()) {
            return clientAddressSources.stream()
                                       .map(name -> "PROXY_PROTOCOL".equals(name) ? ofProxyProtocol()
                                                                                  : ofHeader(name))
                                       .collect(toImmutableList());
        }

        if (useDefaultSources) {
            final Builder<ClientAddressSource> builder = new Builder<>();
            builder.add(ofHeader(HttpHeaderNames.FORWARDED));
            builder.add(ofHeader(HttpHeaderNames.X_FORWARDED_FOR));
            if (specifiedProxyProtocol) {
                builder.add(ofProxyProtocol());
            }
            return builder.build();
        }

        return ImmutableList.of();
    }
}
