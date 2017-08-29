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

import static com.linecorp.centraldogma.it.TestConstants.randomText;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class CentralDogmaRuleWithScaffolding extends CentralDogmaRule {

    private final String testProject = 'p' + randomText();
    private final String testRepository1 = 'r' + randomText();
    private final String testRepository2 = 'r' + randomText();
    private final String removedProject = "rp" + randomText();
    private final String removedRepository = "rr" + randomText();
    private final String missingProject = "mp" + randomText();
    private final String missingRepository = "mr" + randomText();

    public String project() {
        return testProject;
    }

    public String repo1() {
        return testRepository1;
    }

    public String repo2() {
        return testRepository2;
    }

    public String removedProject() {
        return removedProject;
    }

    public String removedRepo() {
        return removedRepository;
    }

    public String missingProject() {
        return missingProject;
    }

    public String missingRepo() {
        return missingRepository;
    }

    public void importDirectory(String resourceDir, String targetDir) {
        requireNonNull(resourceDir, "resourceDir");

        final URL resourceUrl =
                CentralDogmaRuleWithScaffolding.class.getClassLoader().getResource(resourceDir);

        if (resourceUrl == null) {
            throw new IllegalArgumentException("non-existing resource directory: " + resourceDir);
        }

        if (!"file".equals(resourceUrl.getProtocol())) {
            throw new IllegalArgumentException("resource directory is not on a file system: " + resourceDir);
        }

        File f;
        try {
            f = new File(resourceUrl.toURI());
        } catch (URISyntaxException ignored) {
            f = new File(resourceUrl.getPath());
        }

        final List<Change<?>> changes = Change.fromDirectory(f.toPath(), targetDir);

        client().push(testProject, testRepository1, Revision.HEAD, TestConstants.AUTHOR,
                      "Import " + resourceDir + " into " + targetDir, changes).join();
    }

    @Override
    protected void scaffold(CentralDogma client) {
        // Generate a test project with its test repositories.
        client().createProject(testProject).join();
        client().createRepository(testProject, testRepository1).join();
        client().createRepository(testProject, testRepository2).join();

        // Generate a removed project.
        client().createProject(removedProject).join();
        client().removeProject(removedProject).join();

        // Generate a removed repository.
        client().createRepository(testProject, removedRepository).join();
        client().removeRepository(testProject, removedRepository).join();

        // Import the initial content.
        importDirectory(CentralDogmaRuleWithScaffolding.class.getPackage().getName().replace('.', '/') +
                        "/import/", "/test/");
    }
}
