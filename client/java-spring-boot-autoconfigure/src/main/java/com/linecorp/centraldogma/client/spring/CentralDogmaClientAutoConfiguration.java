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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;

import javax.inject.Qualifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.MethodMetadata;

import com.google.common.net.HostAndPort;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.client.armeria.ArmeriaClientConfigurator;

/**
 * Spring bean configuration for {@link CentralDogma} client.
 */
@Configuration
@ConditionalOnMissingBean(CentralDogma.class)
@EnableConfigurationProperties(CentralDogmaSettings.class)
public class CentralDogmaClientAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaClientAutoConfiguration.class);

    /**
     * A {@link Qualifier} annotation that tells {@link CentralDogmaClientAutoConfiguration} to use a specific
     * {@link ClientFactory} bean when creating a {@link CentralDogma} client.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    public @interface ForCentralDogma {}

    /**
     * Returns {@link ClientFactory#ofDefault()} which is used as the default {@link ClientFactory} of a
     * {@link CentralDogma} client.
     */
    @Bean
    @Conditional(MissingCentralDogmaClientFactory.class)
    @ForCentralDogma
    public ClientFactory dogmaClientFactory() {
        return ClientFactory.ofDefault();
    }

    /**
     * Returns a newly created {@link CentralDogma} client.
     */
    @Bean
    public CentralDogma client(
            Environment env,
            CentralDogmaSettings settings,
            @ForCentralDogma ClientFactory clientFactory,
            Optional<ArmeriaClientConfigurator> armeriaClientConfigurator) throws UnknownHostException {

        final ArmeriaCentralDogmaBuilder builder = new ArmeriaCentralDogmaBuilder();

        builder.clientFactory(clientFactory);
        builder.clientConfigurator(cb -> armeriaClientConfigurator.ifPresent(
                configurator -> configurator.configure(cb)));

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

        return builder.build();
    }

    /**
     * A {@link Condition} that matches only when there are no {@link ClientFactory} beans annotated with
     * the {@link ForCentralDogma} qualifier.
     */
    private static class MissingCentralDogmaClientFactory implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            final ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
            if (beanFactory == null) {
                return true;
            }

            final String[] beanNames =
                    BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, ClientFactory.class);

            for (String beanName : beanNames) {
                if (hasQualifier(beanFactory, beanName)) {
                    return false;
                }
            }

            return true;
        }

        private static boolean hasQualifier(ConfigurableListableBeanFactory beanFactory, String beanName) {
            try {
                final BeanDefinition beanDef = beanFactory.getMergedBeanDefinition(beanName);

                // Case 1: Factory method
                if (beanDef instanceof AnnotatedBeanDefinition) {
                    final AnnotatedBeanDefinition abd = (AnnotatedBeanDefinition) beanDef;
                    final MethodMetadata fmm = abd.getFactoryMethodMetadata();
                    if (fmm != null && fmm.isAnnotated(ForCentralDogma.class.getName())) {
                        return true;
                    }
                }

                // Case 2: XML definition
                if (beanDef instanceof AbstractBeanDefinition) {
                    final AbstractBeanDefinition abd = (AbstractBeanDefinition) beanDef;
                    if (abd.hasQualifier(ForCentralDogma.class.getName())) {
                        return true;
                    }
                }

                // Case 3: A class annotated with ForCentralDogma
                final Class<?> beanType = beanFactory.getType(beanName);
                if (beanType != null) {
                    if (AnnotationUtils.getAnnotation(beanType, ForCentralDogma.class) != null) {
                        return true;
                    }
                }
            } catch (NoSuchBeanDefinitionException ignored) {
                // A bean without definition (manually registered?)
            }
            return false;
        }
    }
}
