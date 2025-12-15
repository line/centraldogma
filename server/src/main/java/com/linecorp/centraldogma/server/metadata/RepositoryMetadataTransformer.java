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
package com.linecorp.centraldogma.server.metadata;

import java.util.Map.Entry;
import java.util.function.BiFunction;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.metadata.ProjectMetadataTransformer;

final class RepositoryMetadataTransformer extends ProjectMetadataTransformer {

    RepositoryMetadataTransformer(String repoName,
                                  BiFunction<Revision, RepositoryMetadata, RepositoryMetadata> transformer) {
        super((headRevision, projectMetadata) -> {
            final RepositoryMetadata repositoryMetadata = projectMetadata.repo(repoName);
            assert repositoryMetadata.name().equals(repoName);
            final RepositoryMetadata newRepositoryMetadata =
                    transformer.apply(headRevision, repositoryMetadata);
            return newProjectMetadata(projectMetadata, newRepositoryMetadata);
        });
    }

    private static ProjectMetadata newProjectMetadata(ProjectMetadata projectMetadata,
                                                      RepositoryMetadata repositoryMetadata) {
        final ImmutableMap.Builder<String, RepositoryMetadata> builder =
                ImmutableMap.builderWithExpectedSize(projectMetadata.repos().size());
        for (Entry<String, RepositoryMetadata> entry : projectMetadata.repos().entrySet()) {
            if (entry.getKey().equals(repositoryMetadata.name())) {
                builder.put(entry.getKey(), repositoryMetadata);
            } else {
                builder.put(entry);
            }
        }
        final ImmutableMap<String, RepositoryMetadata> newRepos = builder.build();
        return new ProjectMetadata(projectMetadata.name(),
                                   newRepos,
                                   projectMetadata.members(),
                                   projectMetadata.appIds(),
                                   projectMetadata.creation(),
                                   projectMetadata.removal());
    }
}
