/*
 * Copyright 2024 LINE Corporation
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

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A mirror access controller that can allow or disallow access to the remote repositories for mirroring.
 */
public interface MirrorAccessController {

    /**
     * Allow access to a Git repository URI that matches the specified pattern.
     *
     * @param targetPattern the pattern to match the Git repository URI
     * @param reason the reason for allowing access
     * @param order the order of the access control. The lower the order, the higher the priority.
     */
    CompletableFuture<Boolean> allow(String targetPattern, String reason, int order);

    /**
     * Disallow access to a Git repository URI that matches the specified pattern.
     *
     * @param targetPattern the pattern to match the Git repository URI
     * @param reason the reason for disallowing access
     * @param order the order of the access control. The lower the order, the higher the priority.
     */
    CompletableFuture<Boolean> disallow(String targetPattern, String reason, int order);

    /**
     * Check whether the specified Git repository URI is allowed to be mirrored.
     */
    default CompletableFuture<Boolean> isAllowed(URI repoUri) {
        return isAllowed(repoUri.toString());
    }

    /**
     * Check whether the specified Git repository URI is allowed to be mirrored.
     */
    CompletableFuture<Boolean> isAllowed(String repoUri);

    /**
     * Check whether the specified {@link Mirror} is allowed to be mirrored.
     */
    default CompletableFuture<Boolean> isAllowed(Mirror mirror) {
        // XXX(ikhoon): Should we need to control access to the path or the branch of a mirror?
        return isAllowed(mirror.remoteRepoUri().toString());
    }

    /**
     * Check whether the specified Git repository URIs are allowed to be mirrored.
     */
    CompletableFuture<Map<String, Boolean>> isAllowed(Iterable<String> repoUris);
}
