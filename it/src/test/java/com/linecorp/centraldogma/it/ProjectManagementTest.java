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

import static com.linecorp.centraldogma.internal.thrift.ErrorCode.BAD_REQUEST;
import static com.linecorp.centraldogma.internal.thrift.ErrorCode.PROJECT_EXISTS;
import static com.linecorp.centraldogma.internal.thrift.ErrorCode.PROJECT_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.CompletionException;

import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.internal.thrift.CentralDogmaException;

public class ProjectManagementTest {

    @ClassRule
    public static final CentralDogmaRuleWithScaffolding rule = new CentralDogmaRuleWithScaffolding();

    private static final Logger logger = LoggerFactory.getLogger(ProjectManagementTest.class);

    @Test
    public void testUnremoveProject() throws Exception {
        try {
            rule.client().unremoveProject(rule.removedProject()).join();
            final Set<String> projects = rule.client().listProjects().join();
            assertThat(projects).contains(rule.removedProject());
        } finally {
            try {
                rule.client().removeProject(rule.removedProject()).join();
            } catch (Exception e) {
                logger.warn("Failed to re-remove a project: {}", rule.removedProject(), e);
            }
        }
    }

    @Test
    public void testCreateProjectFailures() throws Exception {
        assertThatThrownBy(() -> rule.client().createProject(rule.project()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == PROJECT_EXISTS);

        // It is not allowed to create a new project whose name is same with the removed project.
        assertThatThrownBy(() -> rule.client().createProject(rule.removedProject()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == PROJECT_EXISTS);

        assertThatThrownBy(() -> rule.client().createProject("..").join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == BAD_REQUEST);
    }

    @Test
    public void testRemoveProjectFailures() throws Exception {
        assertThatThrownBy(() -> rule.client().removeProject(rule.missingProject()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == PROJECT_NOT_FOUND);
    }

    @Test
    public void testListProjects() throws Exception {
        final Set<String> names = rule.client().listProjects().join();

        // Should contain "test.nnn"
        assertThat(names).contains(rule.project());
    }
}
