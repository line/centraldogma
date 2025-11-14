/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.centraldogma.it;

import static com.linecorp.centraldogma.testing.internal.ExpectedExceptionAppender.assertThatThrownByWithExpectedException;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.RepositoryInfo;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.Project;

class RepositoryManagementTest {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryManagementTest.class);

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding();

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void unremoveRepository(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);

        try {
            client.unremoveRepository(dogma.project(), dogma.removedRepo()).join();
            final Map<String, RepositoryInfo> repos = client.listRepositories(dogma.project()).join();
            assertThat(repos).containsKey(dogma.removedRepo());
        } finally {
            try {
                client.removeRepository(dogma.project(), dogma.removedRepo()).join();
            } catch (Exception e) {
                logger.warn("Failed to re-remove a project: {}", dogma.removedProject(), e);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void createRepositoryFailures(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);

        assertThatThrownByWithExpectedException(RepositoryExistsException.class, "repository: r", () ->
                client.createRepository(dogma.project(), dogma.repo1()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(RepositoryExistsException.class);

        assertThatThrownByWithExpectedException(RepositoryExistsException.class, "repository: rr", () ->
                // It is not allowed to create a new repository whose name is same with the removed repository.
                client.createRepository(dogma.project(), dogma.removedRepo()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(RepositoryExistsException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void removeRepositoryFailures(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        assertThatThrownByWithExpectedException(
                RepositoryNotFoundException.class, "repository: mr", () ->
                        client.removeRepository(dogma.project(), dogma.missingRepo()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(RepositoryNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void purgeRepository(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);

        try {
            client.purgeRepository(dogma.project(), dogma.removedRepo()).join();
            final Map<String, RepositoryInfo> repos = client.listRepositories(dogma.project()).join();
            assertThat(repos).doesNotContainKeys(dogma.removedRepo());
            final Set<String> removedRepos = client.listRemovedRepositories(dogma.project()).join();
            assertThat(removedRepos).doesNotContain(dogma.removedRepo());
        } finally {
            // Revert a removed project.
            client.createRepository(dogma.project(), dogma.removedRepo()).join();
            client.removeRepository(dogma.project(), dogma.removedRepo()).join();
        }
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void listRepositories(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final Map<String, RepositoryInfo> repos = client.listRepositories(dogma.project()).join();

        // Should contain 2 "rNNN"s
        assertThat(repos.keySet()).containsExactlyInAnyOrder(Project.REPO_DOGMA, dogma.repo1(), dogma.repo2());

        for (RepositoryInfo r : repos.values()) {
            final Revision headRev = r.headRevision();
            assertThat(headRev).isNotNull();
        }
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void listRemovedRepositories(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final Set<String> repos = client.listRemovedRepositories(dogma.project()).join();
        assertThat(repos).containsExactly(dogma.removedRepo());
    }
}
