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

package com.linecorp.centraldogma.server.storage.repository;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.server.mirror.Mirror;

/**
 * A Revision-controlled filesystem-like repository which is named {@code "meta"}.
 */
public interface MetaRepository extends Repository {

    /**
     * The file path for credentials.
     */
    String PATH_CREDENTIALS = "/credentials.json";

    /**
     * The file path for mirrors.
     */
    String PATH_MIRRORS = "/mirrors.json";

    /**
     * The fil paths for credentials and mirrors.
     */
    Set<String> metaRepoFiles = ImmutableSet.of(PATH_CREDENTIALS, PATH_MIRRORS);

    /**
     * Returns a set of mirroring tasks.
     */
    Set<Mirror> mirrors();
}
