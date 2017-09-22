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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.inject.Qualifier;

/**
 * Annotates a type to provide the necessary information to {@link CentralDogmaBeanFactory} so that the
 * bean properties are mirrored from a file in Central Dogma.
 *
 * <pre>{@code
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
 * > }
 * }</pre>
 *
 * @see CentralDogmaBeanConfig
 */
@Qualifier
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CentralDogmaBean {
    /**
     * Central Dogma project name.
     */
    String project() default "";

    /**
     * Central Dogma repository name.
     */
    String repository() default "";

    /**
     * The path of the file in Central Dogma.
     */
    String path() default "";

    /**
     * The JSON path expression that will be evaluated when retrieving the file at the {@link #path()}.
     */
    String jsonPath() default "";

    /**
     * If {@code true}, the change of each bean property will be pushed to Central Dogma.
     * Use this property with caution because it can result in unnecessarily large number of commits.
     */
    boolean bidirectional() default false;
}
