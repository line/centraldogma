/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import com.linecorp.centraldogma.common.Change;

/**
 * Validates file changes before they are committed to a repository during mirroring.
 *
 * <p>Implementations are loaded via {@link java.util.ServiceLoader} and invoked in
 * {@link com.linecorp.centraldogma.server.internal.mirror.AbstractMirror} before each push.</p>
 */
@FunctionalInterface
public interface MirrorFileValidator {

    /**
     * Validates a file change before it is committed to a repository during mirroring.
     */
    void validate(String projectName, String repoName, Change<?> change);
}
