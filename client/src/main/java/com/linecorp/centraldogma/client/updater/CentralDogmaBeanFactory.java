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
package com.linecorp.centraldogma.client.updater;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Latest;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.common.Query;

import javassist.util.proxy.ProxyFactory;

/**
 * Creates a new bean instance that synchronizes each values with CentralDogma.
 */
@Named
public class CentralDogmaBeanFactory {
    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaBeanFactory.class);

    private static final Class<?>[] EMPTY_TYPES = new Class<?>[0];
    private static final Object[] EMPTY_ARGS = new Object[0];

    private final CentralDogma centralDogma;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new factory instance.
     */
    @Inject
    public CentralDogmaBeanFactory(CentralDogma centralDogma, ObjectMapper objectMapper) {
        this.centralDogma = centralDogma;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new bean instance with {@code override} settings.
     *
     * @param bean an instance of {@link T} which may be initialized its fields with default values.
     * @param beanClass an {@link Class} instance which points a class used for deserializing value.
     * @param override an {@link CentralDogmaBeanConfig} which can be used to override config supplied through
     * annotation.
     * @param initialValueTimeout when a value larger than zero given to this argument this method
     * tries to wait the initial value to be fetched up to {@code initialValueTimeoutMillis} milliseconds.
     * @param initialValueTimeoutUnit a {@link TimeUnit} which qualifies {@code initialValueTimeout}
     * @param <T> an representation class of target parameters.
     *
     * @return an instance of {@link T} which has proxied getters for obtaining latest values configured on
     * Central Dogma server.
     *
     * @throws TimeoutException thrown when {@code initialValueTimeoutMillis} set to more than zero and it
     * fails to obtain the initial value within configured timeout.
     * @throws InterruptedException thrown when {@code initialValueTimeoutMillis} set to more than zero and it
     * got interrupted while waiting initial value to be fetched.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(T bean, Class<T> beanClass, CentralDogmaBeanConfig override,
                     long initialValueTimeout, TimeUnit initialValueTimeoutUnit)
            throws TimeoutException, InterruptedException {
        requireNonNull(bean, "bean");
        requireNonNull(beanClass, "beanClass");
        requireNonNull(override, "override");
        requireNonNull(initialValueTimeoutUnit, "initialValueTimeoutUnit");

        CentralDogmaBean centralDogmaBean = bean.getClass()
                                                .getAnnotation(CentralDogmaBean.class);
        if (centralDogmaBean == null) {
            throw new IllegalArgumentException("missing CentralDogmaBean annotation");
        }
        CentralDogmaBeanConfig settings = merge(convertToSettings(centralDogmaBean), override);
        checkState(!isNullOrEmpty(settings.project().get()), "settings.projectName should non-null");
        checkState(!isNullOrEmpty(settings.repository().get()), "settings.repositoryName should non-null");
        checkState(!isNullOrEmpty(settings.path().get()), "settings.fileName should non-null");

        final Watcher<T> watcher = centralDogma.fileWatcher(
                settings.project().get(),
                settings.repository().get(),
                buildQuery(settings),
                jsonNode -> {
                    try {
                        return objectMapper.treeToValue(jsonNode, beanClass);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(
                                "Failed to convert a JSON node into: " + beanClass.getName(), e);
                    }
                });

        if (initialValueTimeout > 0) {
            long t0 = System.nanoTime();
            Latest<T> latest = watcher.awaitInitialValue(initialValueTimeout, initialValueTimeoutUnit);
            long elapsedMillis = System.nanoTime() - t0;
            logger.debug("Initial value of {} obtained in {} ms: rev={}",
                         settings, elapsedMillis, latest.revision());
        }

        final ProxyFactory factory = new ProxyFactory();
        factory.setSuperclass(beanClass);
        factory.setFilter(method -> {
            // Looks like getter method
            if (method.getParameterCount() == 0) {
                return true;
            }
            // The property is bidirectional and the method looks like setter method
            return centralDogmaBean.bidirectional() && method.getParameterCount() == 1;
        });
        try {
            return (T) factory.create(EMPTY_TYPES, EMPTY_ARGS,
                                      new CentralDogmaBeanMethodHandler<>(watcher, bean));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a new bean instance with {@code override} settings.
     *
     * @param bean an instance of {@link T} which may be initialized its fields with default values.
     * @param beanClass an {@link Class} instance which points a class used for deserializing value.
     * @param override an {@link CentralDogmaBeanConfig} which can be used to override config supplied through
     * annotation.
     * @param <T> an representation class of target parameters.
     *
     * @return an instance of {@link T} which has proxied getters for obtaining latest values configured on
     * Central Dogma server.
     */
    public <T> T get(T bean, Class<T> beanClass, CentralDogmaBeanConfig override) {
        try {
            return get(bean, beanClass, override, 0L, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException e) {
            // when initialValueTimeoutMillis set to zero, this exception never happens in practice.
            throw new RuntimeException("Error: unexpected exception caught", e);
        }
    }

    /**
     * Create a new bean instance with default settings.
     *
     * @param bean an instance of {@link T} which may be initialized its fields with default values.
     * @param beanClass an {@link Class} instance which points a class used for deserializing value.
     * annotation.
     * @param initialValueTimeout when a value larger than zero given to this argument this method
     * tries to wait the initial value to be fetched up to {@code initialValueTimeoutMillis} milliseconds.
     * @param initialValueTimeoutUnit a {@link TimeUnit} which qualifies {@code initialValueTimeout}
     * @param <T> an representation class of target parameters.
     *
     * @return an instance of {@link T} which has proxied getters for obtaining latest values configured on
     * Central Dogma server.
     *
     * @throws TimeoutException thrown when {@code initialValueTimeoutMillis} set to more than zero and it
     * fails to obtain the initial value within configured timeout.
     * @throws InterruptedException thrown when {@code initialValueTimeoutMillis} set to more than zero and it
     * got interrupted while waiting initial value to be fetched.
     */
    public <T> T get(T bean, Class<T> beanClass, long initialValueTimeout, TimeUnit initialValueTimeoutUnit)
            throws TimeoutException, InterruptedException {
        return get(bean, beanClass, CentralDogmaBeanConfig.DEFAULT,
                   initialValueTimeout, initialValueTimeoutUnit);
    }

    /**
     * Create a new bean instance with default settings.
     */
    public <T> T get(T property, Class<T> propertyClass) {
        return get(property, propertyClass, CentralDogmaBeanConfig.DEFAULT);
    }

    private static CentralDogmaBeanConfig convertToSettings(CentralDogmaBean property) {
        return new CentralDogmaBeanConfigBuilder()
                .project(property.project())
                .repository(property.repository())
                .path(property.path())
                .jsonPath(property.jsonPath())
                .build();
    }

    private static CentralDogmaBeanConfig merge(CentralDogmaBeanConfig defaultSettings,
                                                CentralDogmaBeanConfig overridden) {
        return new CentralDogmaBeanConfigBuilder(defaultSettings).add(overridden).build();
    }

    private static Query<JsonNode> buildQuery(CentralDogmaBeanConfig config) {
        checkArgument(config.path().get().endsWith(".json"),
                      "path: %s (expected: ends with '.json')", config.path().get());
        return Query.ofJsonPath(config.path().get(), config.jsonPath().get());
    }
}
