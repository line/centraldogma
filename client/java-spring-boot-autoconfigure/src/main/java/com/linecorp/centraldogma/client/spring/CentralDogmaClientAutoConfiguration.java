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
import java.util.Optional;

import javax.inject.Qualifier;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.MethodMetadata;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.centraldogma.client.ArmeriaClientConfigurator;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaBuilder;

/**
 * Spring bean configuration for {@link CentralDogma} client.
 */
@Configuration
@ConditionalOnMissingBean(CentralDogma.class)
public class CentralDogmaClientAutoConfiguration {

    /**
     * A {@link Qualifier} annotation that tells {@link CentralDogmaClientAutoConfiguration} to use a specific
     * {@link ClientFactory} bean when creating a {@link CentralDogma} client.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    public @interface ForCentralDogma {
    }

    /**
     * Returns {@link ClientFactory#DEFAULT} which is used as the default {@link ClientFactory} of a
     * {@link CentralDogma} client.
     */
    @Bean
    @Conditional(MissingCentralDogmaClientFactory.class)
    @ForCentralDogma
    public ClientFactory dogmaClientFactory() {
        return ClientFactory.DEFAULT;
    }

    /**
     * Returns a newly created {@link CentralDogma} client.
     */
    @Bean
    public CentralDogma client(
            Environment env,
            @ForCentralDogma ClientFactory clientFactory,
            Optional<ArmeriaClientConfigurator> armeriaClientConfigurator) {

        return new CentralDogmaBuilder()
                .clientFactory(clientFactory)
                .profile(env.getActiveProfiles())
                .clientConfigurator(cb -> armeriaClientConfigurator.ifPresent(
                        configurator -> configurator.configure(cb)))
                .build();
    }

    /**
     * A {@link Condition} that matches only when there are no {@link ClientFactory} beans annotated with
     * the {@link ForCentralDogma} qualifier.
     */
    private static class MissingCentralDogmaClientFactory implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            final ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
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
                    if (fmm.isAnnotated(ForCentralDogma.class.getName())) {
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
