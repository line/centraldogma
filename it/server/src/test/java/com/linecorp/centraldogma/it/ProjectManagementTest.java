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

import java.util.Set;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;

class ProjectManagementTest {

    private static final Logger logger = LoggerFactory.getLogger(ProjectManagementTest.class);

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding();

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void unremoveProject(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);

        try {
            client.unremoveProject(dogma.removedProject()).join();
            final Set<String> projects = client.listProjects().join();
            assertThat(projects).contains(dogma.removedProject());
        } finally {
            try {
                client.removeProject(dogma.removedProject()).join();
            } catch (Exception e) {
                logger.warn("Failed to re-remove a project: {}", dogma.removedProject(), e);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void createProjectFailures(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);

        assertThatThrownByWithExpectedException(ProjectExistsException.class, "project: p", () ->
                client.createProject(dogma.project()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ProjectExistsException.class);

        assertThatThrownByWithExpectedException(ProjectExistsException.class, "project: rp", () ->
                // It is not allowed to create a new project whose name is same with the removed project.
                client.createProject(dogma.removedProject()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ProjectExistsException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void removeProjectFailures(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        assertThatThrownByWithExpectedException(ProjectNotFoundException.class, "project: mp", () ->
                client.removeProject(dogma.missingProject()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(ProjectNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void listProjects(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final Set<String> names = client.listProjects().join();

        // Should contain "test.nnn"
        assertThat(names).contains(dogma.project());
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void listRemovedProjects(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final Set<String> names = client.listRemovedProjects().join();
        assertThat(names).containsExactly(dogma.removedProject());
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void purgeProject(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        client.purgeProject(dogma.removedProject()).join();
        final Set<String> names = client.listRemovedProjects().join();
        assertThat(names).doesNotContain(dogma.removedProject());
        // revert the purged project
        client.createProject(dogma.removedProject()).join();
        client.removeProject(dogma.removedProject()).join();
    }
}
