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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Qualifier;

/**
 * Annotation to indicate the value(s) of instance fields in annotated class synchronize(s) with Central Dogma.
 * Each properties in this annotation can be overridden by {@link CentralDogmaBeanConfig} when creating
 * a property bean through {@link CentralDogmaBeanFactory#get(Object, Class, CentralDogmaBeanConfig)}.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface CentralDogmaBean {
    /**
     * Name of the project that properties synchronize with Central Dogma.
     */
    String project() default "";

    /**
     * Name of the repository that properties synchronize with Central Dogma.
     */
    String repository() default "";

    /**
     * Name of the path synchronize with Central Dogma.
     */
    String path() default "";

    /**
     * A JSONPath expression that will be executed when retriving the data from Central Dogma.
     */
    String jsonPath() default "$";

    /**
     * If {@code true}, then the change of each field will be committed to Central Dogma.
     */
    boolean bidirectional() default false;
}
