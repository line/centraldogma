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
package com.linecorp.centraldogma.it;

import static com.linecorp.centraldogma.testing.internal.ExpectedExceptionAppender.assertThatThrownByWithExpectedException;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.CompletionException;

import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;

public class ProjectManagementTest extends AbstractMultiClientTest {

    @ClassRule
    public static final CentralDogmaRuleWithScaffolding rule = new CentralDogmaRuleWithScaffolding();

    private static final Logger logger = LoggerFactory.getLogger(ProjectManagementTest.class);

    public ProjectManagementTest(ClientType clientType) {
        super(clientType);
    }

    @Test
    public void testUnremoveProject() throws Exception {
        try {
            client().unremoveProject(rule.removedProject()).join();
            final Set<String> projects = client().listProjects().join();
            assertThat(projects).contains(rule.removedProject());
        } finally {
            try {
                client().removeProject(rule.removedProject()).join();
            } catch (Exception e) {
                logger.warn("Failed to re-remove a project: {}", rule.removedProject(), e);
            }
        }
    }

    @Test
    public void testCreateProjectFailures() throws Exception {
        assertThatThrownByWithExpectedException(ProjectExistsException.class, "project: p", () ->
                client().createProject(rule.project()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ProjectExistsException.class);

        assertThatThrownByWithExpectedException(ProjectExistsException.class, "project: rp", () ->
                // It is not allowed to create a new project whose name is same with the removed project.
                client().createProject(rule.removedProject()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ProjectExistsException.class);
    }

    @Test
    public void testRemoveProjectFailures() throws Exception {
        assertThatThrownByWithExpectedException(ProjectNotFoundException.class, "project: mp", () ->
                client().removeProject(rule.missingProject()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    public void testListProjects() throws Exception {
        final Set<String> names = client().listProjects().join();

        // Should contain "test.nnn"
        assertThat(names).contains(rule.project());
    }

    @Test
    public void testListRemovedProjects() throws Exception {
        final Set<String> names = client().listRemovedProjects().join();
        assertThat(names).containsExactly(rule.removedProject());
    }
}
