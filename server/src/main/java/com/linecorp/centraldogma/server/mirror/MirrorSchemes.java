/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.server.mirror;

/**
 * URL schemes used by mirrors.
 */
public final class MirrorSchemes {

    /**
     * {@code "dogma"}.
     */
    public static final String SCHEME_DOGMA = "dogma";

    /**
     * {@code "git"}.
     */
    public static final String SCHEME_GIT = "git";

    /**
     * {@code "git+ssh"}.
     */
    public static final String SCHEME_GIT_SSH = "git+ssh";

    /**
     * {@code "git+http"}.
     */
    public static final String SCHEME_GIT_HTTP = "git+http";

    /**
     * {@code "git+https"}.
     */
    public static final String SCHEME_GIT_HTTPS = "git+https";

    /**
     * {@code "git+file"}.
     */
    public static final String SCHEME_GIT_FILE = "git+file";

    private MirrorSchemes() {}
}
