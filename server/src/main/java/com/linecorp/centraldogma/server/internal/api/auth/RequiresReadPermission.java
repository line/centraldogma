/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.api.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresPermissionDecorator.RequiresReadPermissionDecoratorFactory;
import com.linecorp.centraldogma.server.metadata.Permission;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A {@link Decorator} to allow a request from a user who has a read {@link Permission}.
 */
@DecoratorFactory(RequiresReadPermissionDecoratorFactory.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface RequiresReadPermission {
    /**
     * A special parameter in order to specify the order of a {@link Decorator}.
     */
    int order() default 0;

    /**
     * The name of the {@link Project} to check the permission.
     * If not specified, the project name will be extracted from the {@code projectName} path variable.
     */
    String project() default "";

    /**
     * The name of the {@link Repository} to check the permission.
     * If not specified, the repository name will be extracted from the {@code repoName} path variable.
     */
    String repository() default "";
}
