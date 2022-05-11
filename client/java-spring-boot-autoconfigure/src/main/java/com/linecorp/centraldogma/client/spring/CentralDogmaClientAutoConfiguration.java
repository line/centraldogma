/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.centraldogma.client.spring;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.google.common.net.HostAndPort;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.client.armeria.ArmeriaClientConfigurator;
import com.linecorp.centraldogma.client.armeria.DnsAddressEndpointGroupConfigurator;

/**
 * Spring bean configuration for {@link CentralDogma} client.
 */
@Configuration
@ConditionalOnMissingBean(CentralDogma.class)
@EnableConfigurationProperties(CentralDogmaSettings.class)
public class CentralDogmaClientAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaClientAutoConfiguration.class);

    static final long DEFAULT_INITIALIZATION_TIMEOUT_MILLIS = 15;

    /**
     * Returns a newly created {@link CentralDogma} client.
     */
    @Bean
    public CentralDogma dogmaClient(
            Environment env,
            CentralDogmaSettings settings,
            Optional<List<CentralDogmaClientFactoryConfigurator>> factoryConfigurators,
            Optional<ArmeriaClientConfigurator> armeriaClientConfigurator,
            Optional<DnsAddressEndpointGroupConfigurator> dnsAddressEndpointGroupConfigurator)
            throws UnknownHostException {

        final ArmeriaCentralDogmaBuilder builder = new ArmeriaCentralDogmaBuilder();

        if (factoryConfigurators.isPresent()) {
            final ClientFactoryBuilder clientFactoryBuilder = ClientFactory.builder();
            factoryConfigurators.get().forEach(configurator -> configurator.configure(clientFactoryBuilder));
            builder.clientFactory(clientFactoryBuilder.build());
        }

        builder.clientConfigurator(cb -> armeriaClientConfigurator.ifPresent(
                configurator -> configurator.configure(cb)));
        dnsAddressEndpointGroupConfigurator.ifPresent(builder::dnsAddressEndpointGroupConfigurator);

        // Set health check interval.
        final Long healthCheckIntervalMillis = settings.getHealthCheckIntervalMillis();
        if (healthCheckIntervalMillis != null) {
            builder.healthCheckIntervalMillis(healthCheckIntervalMillis);
        }

        // Enable or disable TLS.
        final Boolean useTls = settings.getUseTls();
        if (useTls != null) {
            builder.useTls(useTls);
        }

        // Set access token.
        final String accessToken = settings.getAccessToken();
        if (accessToken != null) {
            builder.accessToken(accessToken);
        }

        // Set profile or hosts.
        final String profile = settings.getProfile();
        final List<String> hosts = settings.getHosts();
        if (profile != null) {
            if (hosts != null) {
                throw new IllegalStateException(
                        "'hosts' and 'profile' are mutually exclusive. Do not set both of them.");
            }
            builder.profile(CentralDogmaClientAutoConfiguration.class.getClassLoader(), profile);
        } else if (hosts != null) {
            for (String h : hosts) {
                final HostAndPort hostAndPort = HostAndPort.fromString(h);
                if (hostAndPort.hasPort()) {
                    builder.host(hostAndPort.getHost(), hostAndPort.getPort());
                } else {
                    builder.host(hostAndPort.getHost());
                }
            }
        } else {
            // Use the currently active Spring Boot profiles if neither profile nor hosts was specified.
            final String[] springBootProfiles = env.getActiveProfiles();
            logger.info("Using the Spring Boot profiles as the source of the Central Dogma client profile: {}",
                        springBootProfiles);
            builder.profile(springBootProfiles);
        }

        // Replication lag tolerance settings.
        final Integer maxNumRetriesOnReplicationLag = settings.getMaxNumRetriesOnReplicationLag();
        if (maxNumRetriesOnReplicationLag != null) {
            builder.maxNumRetriesOnReplicationLag(maxNumRetriesOnReplicationLag);
        }

        final Long retryIntervalOnReplicationLagMillis = settings.getRetryIntervalOnReplicationLagMillis();
        if (retryIntervalOnReplicationLagMillis != null) {
            builder.retryIntervalOnReplicationLagMillis(retryIntervalOnReplicationLagMillis);
        }

        final CentralDogma centralDogma = builder.build();
        Long initializationTimeoutMillis = settings.initializationTimeoutMillis();
        if (initializationTimeoutMillis == null) {
            initializationTimeoutMillis = DEFAULT_INITIALIZATION_TIMEOUT_MILLIS;
        }
        if (initializationTimeoutMillis > 0) {
            try {
                centralDogma.whenEndpointReady().get(initializationTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new IllegalStateException("Failed to initialize the endpoints of " + centralDogma, e);
            }
        }
        return centralDogma;
    }
}
