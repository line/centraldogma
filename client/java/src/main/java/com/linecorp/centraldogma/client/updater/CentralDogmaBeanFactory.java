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
import java.util.function.Consumer;

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
 * Creates a new bean instance that mirrors its properties from Central Dogma.
 *
 * <p>In the following example, {@code mirroredFoo.getA()} and {@code getB()} will return the latest known
 * values retrieved from {@code /myProject/myRepo/foo.json} along with the latest revision in Central Dogma.
 * If the latest values are not available yet, the revision will be set to null.
 *
 * <pre>{@code
 * > CentralDogmaFactory factory = new CentralDogmaFactory(dogma, new ObjectMapper());
 * > Foo mirroredFoo = factory.get(new Foo(), Foo.class);
 * >
 * > @CentralDogmaBean(project = "myProject",
 * >                   repository = "myRepo",
 * >                   path = "/foo.json")
 * > public class Foo {
 * >     private int a;
 * >     private String b;
 * >
 * >     public int getA() { return a; }
 * >     public void setA(int a) { this.a = a; }
 * >     public String getB() { return b; }
 * >     public void setB(String b) { this.b = b; }
 * >     public Revision getRevision() { return null; }
 * > }
 * }</pre>
 *
 * <p>In the following example, callback will be called when a property is updated with a new value:
 *
 * <pre>{@code
 * CentralDogmaFactory factory = new CentralDogmaFactory(dogma, new ObjectMapper());
 * Consumer<Foo> fooUpdatedListener = (Foo f) -> {
 *     System.out.println("foo has updated to: " + f);
 * };
 *
 * Foo mirroredFoo = factory.get(new Foo(), Foo.class, fooUpdatedListener);
 * }</pre>
 */
@Named
public class CentralDogmaBeanFactory {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaBeanFactory.class);

    private static final Class<?>[] EMPTY_TYPES = new Class<?>[0];
    private static final Object[] EMPTY_ARGS = new Object[0];

    private final CentralDogma dogma;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new factory instance.
     */
    @Inject
    public CentralDogmaBeanFactory(CentralDogma dogma, ObjectMapper objectMapper) {
        this.dogma = requireNonNull(dogma, "dogma");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Returns a newly-created bean instance with the settings specified by {@link CentralDogmaBean} annotation.
     *
     * @param bean a Java bean annotated with {@link CentralDogmaBean}
     * @param beanType the type of {@code bean}
     *
     * @return a new Java bean whose getters return the latest known values mirrored from Central Dogma
     */
    public <T> T get(T bean, Class<T> beanType) {
        return get(bean, beanType, (T x) -> {
        }, CentralDogmaBeanConfig.EMPTY);
    }

    /**
     * Returns a newly-created bean instance with the settings specified by {@link CentralDogmaBean} annotation.
     *
     * @param bean a Java bean annotated with {@link CentralDogmaBean}
     * @param beanType the type of {@code bean}
     * @param changeListener the {@link Consumer} of {@code beanType}, invoked when {@code bean} is updated
     *
     * @return a new Java bean whose getters return the latest known values mirrored from Central Dogma
     */
    public <T> T get(T bean, Class<T> beanType, Consumer<T> changeListener) {
        return get(bean, beanType, changeListener, CentralDogmaBeanConfig.EMPTY);
    }

    /**
     * Returns a newly-created bean instance with some or all of its settings overridden.
     *
     * @param bean a Java bean annotated with {@link CentralDogmaBean}
     * @param beanType the type of {@code bean}
     * @param changeListener the {@link Consumer} of {@code beanType}, invoked when {@code bean} is updated
     * @param overrides the {@link CentralDogmaBeanConfig} whose properties will override the settings
     *                  specified by the {@link CentralDogmaBean} annotation
     *
     * @return a new Java bean whose getters return the latest known values mirrored from Central Dogma
     */
    public <T> T get(T bean, Class<T> beanType, Consumer<T> changeListener,
                     CentralDogmaBeanConfig overrides) {
        try {
            return get(bean, beanType, changeListener, overrides, 0L, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException e) {
            // when initialValueTimeoutMillis set to zero, this exception never happens in practice.
            throw new RuntimeException("Error: unexpected exception caught", e);
        }
    }

    /**
     * Returns a newly-created bean instance with the settings specified by {@link CentralDogmaBean} annotation.
     *
     * @param bean a Java bean annotated with {@link CentralDogmaBean}
     * @param beanType the type of {@code bean}
     * @param changeListener the {@link Consumer} of {@code beanType}, invoked when {@code bean} is updated
     * @param initialValueTimeout when a value larger than zero given to this argument, this method
     *                            tries to wait for the initial value to be fetched until the timeout
     * @param initialValueTimeoutUnit the {@link TimeUnit} of {@code initialValueTimeout}
     *
     * @return a new Java bean whose getters return the latest known values mirrored from Central Dogma
     *
     * @throws TimeoutException when {@code initialValueTimeoutMillis} is positive and
     *                          it failed to obtain the initial value within configured timeout
     * @throws InterruptedException when {@code initialValueTimeoutMillis} is positive and
     *                              it got interrupted while waiting for the initial value
     */
    public <T> T get(T bean, Class<T> beanType, Consumer<T> changeListener, long initialValueTimeout,
                     TimeUnit initialValueTimeoutUnit)
            throws TimeoutException, InterruptedException {
        return get(bean, beanType, changeListener, CentralDogmaBeanConfig.EMPTY,
                   initialValueTimeout, initialValueTimeoutUnit);
    }

    /**
     * Returns a newly-created bean instance with some or all of its settings overridden.
     *
     * @param bean a Java bean annotated with {@link CentralDogmaBean}
     * @param beanType the type of {@code bean}
     * @param overrides the {@link CentralDogmaBeanConfig} whose properties will override the settings
     *                  specified by the {@link CentralDogmaBean} annotation
     *
     * @return a new Java bean whose getters return the latest known values mirrored from Central Dogma
     */
    public <T> T get(T bean, Class<T> beanType, CentralDogmaBeanConfig overrides) {
        try {
            return get(bean, beanType, overrides, 0L, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException e) {
            // when initialValueTimeoutMillis set to zero, this exception never happens in practice.
            throw new RuntimeException("Error: unexpected exception caught", e);
        }
    }

    /**
     * Returns a newly-created bean instance with the settings specified by {@link CentralDogmaBean} annotation.
     *
     * @param bean a Java bean annotated with {@link CentralDogmaBean}
     * @param beanType the type of {@code bean}
     * @param initialValueTimeout when a value larger than zero given to this argument, this method
     *                            tries to wait for the initial value to be fetched until the timeout
     * @param initialValueTimeoutUnit the {@link TimeUnit} of {@code initialValueTimeout}
     *
     * @return a new Java bean whose getters return the latest known values mirrored from Central Dogma
     *
     * @throws TimeoutException when {@code initialValueTimeoutMillis} is positive and
     *                          it failed to obtain the initial value within configured timeout
     * @throws InterruptedException when {@code initialValueTimeoutMillis} is positive and
     *                              it got interrupted while waiting for the initial value
     */
    public <T> T get(T bean, Class<T> beanType, long initialValueTimeout, TimeUnit initialValueTimeoutUnit)
            throws TimeoutException, InterruptedException {
        return get(bean, beanType, CentralDogmaBeanConfig.EMPTY,
                   initialValueTimeout, initialValueTimeoutUnit);
    }

    /**
     * Returns a newly-created bean instance with some or all of its settings overridden.
     *
     * @param bean a Java bean annotated with {@link CentralDogmaBean}
     * @param beanType the type of {@code bean}
     * @param overrides the {@link CentralDogmaBeanConfig} whose properties will override the settings
     *                  specified by the {@link CentralDogmaBean} annotation
     * @param initialValueTimeout when a value larger than zero given to this argument, this method
     *                            tries to wait for the initial value to be fetched until the timeout
     * @param initialValueTimeoutUnit the {@link TimeUnit} of {@code initialValueTimeout}
     *
     * @return a new Java bean whose getters return the latest known values mirrored from Central Dogma
     *
     * @throws TimeoutException when {@code initialValueTimeoutMillis} is positive and
     *                          it failed to obtain the initial value within configured timeout
     * @throws InterruptedException when {@code initialValueTimeoutMillis} is positive and
     *                              it got interrupted while waiting for the initial value
     */
    @SuppressWarnings("unchecked")
    public <T> T get(T bean, Class<T> beanType, CentralDogmaBeanConfig overrides,
                     long initialValueTimeout, TimeUnit initialValueTimeoutUnit)
            throws TimeoutException, InterruptedException {
        return get(bean, beanType, (T x) -> {
        }, overrides, initialValueTimeout, initialValueTimeoutUnit);
    }

    /**
     * Returns a newly-created bean instance with some or all of its settings overridden.
     *
     * @param bean a Java bean annotated with {@link CentralDogmaBean}
     * @param beanType the type of {@code bean}
     * @param changeListener the {@link Consumer} of {@code beanType}, invoked when {@code bean} is updated
     * @param overrides the {@link CentralDogmaBeanConfig} whose properties will override the settings
     *                  specified by the {@link CentralDogmaBean} annotation
     * @param initialValueTimeout when a value larger than zero given to this argument, this method
     *                            tries to wait for the initial value to be fetched until the timeout
     * @param initialValueTimeoutUnit the {@link TimeUnit} of {@code initialValueTimeout}
     *
     * @return a new Java bean whose getters return the latest known values mirrored from Central Dogma
     *
     * @throws TimeoutException when {@code initialValueTimeoutMillis} is positive and
     *                          it failed to obtain the initial value within configured timeout
     * @throws InterruptedException when {@code initialValueTimeoutMillis} is positive and
     *                              it got interrupted while waiting for the initial value
     */
    @SuppressWarnings("unchecked")
    public <T> T get(T bean, Class<T> beanType, Consumer<T> changeListener,
                     CentralDogmaBeanConfig overrides, long initialValueTimeout,
                     TimeUnit initialValueTimeoutUnit)
            throws TimeoutException, InterruptedException {
        requireNonNull(bean, "bean");
        requireNonNull(beanType, "beanType");
        requireNonNull(overrides, "overrides");
        requireNonNull(initialValueTimeoutUnit, "initialValueTimeoutUnit");

        final CentralDogmaBean centralDogmaBean = bean.getClass().getAnnotation(CentralDogmaBean.class);
        if (centralDogmaBean == null) {
            throw new IllegalArgumentException("missing CentralDogmaBean annotation");
        }

        final CentralDogmaBeanConfig settings = merge(convertToSettings(centralDogmaBean), overrides);
        checkState(!isNullOrEmpty(settings.project().get()), "settings.projectName should non-null");
        checkState(!isNullOrEmpty(settings.repository().get()), "settings.repositoryName should non-null");
        checkState(!isNullOrEmpty(settings.path().get()), "settings.fileName should non-null");

        final Watcher<T> watcher = dogma.fileWatcher(
                settings.project().get(),
                settings.repository().get(),
                buildQuery(settings),
                jsonNode -> {
                    changeListener.accept(bean);
                    try {
                        return objectMapper.treeToValue(jsonNode, beanType);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(
                                "Failed to convert a JSON node into: " + beanType.getName(), e);
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
        factory.setSuperclass(beanType);
        factory.setFilter(method -> {
            // Allow all non-parameter methods (getter/closeWatcher..)
            if (method.getParameterCount() == 0) {
                return true;
            }
            // Allow methods have bidirectional property and looks like setter
            return centralDogmaBean.bidirectional() && method.getParameterCount() == 1;
        });

        try {
            return (T) factory.create(EMPTY_TYPES, EMPTY_ARGS,
                                      new CentralDogmaBeanMethodHandler<>(watcher, bean));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
        return new CentralDogmaBeanConfigBuilder(defaultSettings).merge(overridden).build();
    }

    private static Query<JsonNode> buildQuery(CentralDogmaBeanConfig config) {
        checkArgument(config.path().get().endsWith(".json"),
                      "path: %s (expected: ends with '.json')", config.path().get());
        return Query.ofJsonPath(config.path().get(), config.jsonPath().orElse("$"));
    }
}
