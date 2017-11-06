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

package com.linecorp.centraldogma.server.internal.storage.project;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.plugin.PluginManager;
import com.linecorp.centraldogma.server.internal.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryManager;

public interface Project {
    String REPO_META = "meta";
    String REPO_MAIN = "main";

    String name();

    default long creationTimeMillis() {
        return metaRepo().creationTimeMillis();
    }

    default Author author() {
        return metaRepo().author();
    }

    MetaRepository metaRepo();

    Repository mainRepo();

    RepositoryManager repos();

    PluginManager plugins();
}
