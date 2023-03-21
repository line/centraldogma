/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.mirror;

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.api.FetchCommand;

final class Git6WithAuth extends GitWithAuth {
    Git6WithAuth(GitMirror mirror, File repoDir) throws IOException {
        super(mirror, repoDir);
    }

    @Override
    FetchCommand fetch(int depth) {
        return fetch().setDepth(1);
    }

}
