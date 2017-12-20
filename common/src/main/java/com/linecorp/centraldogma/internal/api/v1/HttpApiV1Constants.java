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

package com.linecorp.centraldogma.internal.api.v1;

/**
 * Constants for HTTP API version 1.
 */
public final class HttpApiV1Constants {

    public static final String API_V1_PATH_PREFIX = "/api/v1/";

    public static final String PROJECTS = "projects";

    public static final String PROJECTS_PREFIX = API_V1_PATH_PREFIX + PROJECTS;

    public static final String REPOS = "/repos";

    public static final String COMMITS = "/commits";

    public static final String COMPARE = "/compare";

    public static final String CONTENTS = "/contents";

    private HttpApiV1Constants() {}
}
