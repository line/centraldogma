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

package com.linecorp.centraldogma.server.internal.plugin;

import static com.linecorp.centraldogma.server.internal.storage.project.Project.REPO_MAIN;
import static com.linecorp.centraldogma.server.internal.storage.project.Project.REPO_META;
import static java.util.Objects.requireNonNull;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

@Ignore("Nashorn raises an AssertionError for an unknown reason.")
public class PluginTest {

    @ClassRule
    public static final TemporaryFolder rootDir = new TemporaryFolder();

    private static ProjectManager pm;

    @Rule
    public TestName testName = new TestName();

    @BeforeClass
    public static void beforeClass() throws Exception {
        pm = new DefaultProjectManager(rootDir.getRoot(), ForkJoinPool.commonPool(), null);
    }

    @AfterClass
    public static void afterClass() throws Exception {
        if (pm != null) {
            pm.close();
            pm = null;
        }
    }

    @Test(timeout = 5000)
    public void testHelloWorld() throws Exception {
        final Project p = newProject();
        assertThat(p.plugins().invoke("hello", "world"), is("Hello, world!"));
    }

    @Test(timeout = 5000)
    public void testRequireJs() throws Exception {
        final Project p = newProject();
        assertThat(p.plugins().invoke("hello", "world"), is("Howdy, 'world'!"));
        assertThat(p.plugins().invoke("loadCount"), is(1));
    }

    @Test(timeout = 5000)
    public void testPromise() throws Exception {
        final Project p = newProject();
        assertThat(p.plugins().invoke("hello", "world"), is("Hellworldo, !"));
    }

    @Test(timeout = 5000)
    public void testLoadFailure() throws Exception {
        try {
            newProject();
        } catch (PluginException e) {
            // Get the stack trace string of the exception.
            final StringWriter writer = new StringWriter();
            e.printStackTrace(new PrintWriter(writer));
            final String stackTrace = writer.toString();

            // Ensure the stack trace contains the location of the offending JavaScript file (/fail.js).
            final Pattern tracePattern = Pattern.compile("\\n[ \\t]+at [^(]+\\(/fail\\.js:[0-9]+\\)\\r?\\n");
            if (!tracePattern.matcher(stackTrace).find()) {
                fail("PluginException does not contain the trace information: " + stackTrace);
            }
        }
    }

    private Project newProject() {
        final Project p = pm.create(testName.getMethodName());
        p.repos().create(REPO_META);
        p.repos().create(REPO_MAIN);
        p.metaRepo().commit(Revision.HEAD, 0L, Author.SYSTEM, "", loadChanges("meta"));
        p.plugins().reload();
        return p;
    }

    // TODO(trustin): Consider de-duping from AbstractCentralDogmaServiceV1Test.
    private List<Change<?>> loadChanges(String repoName) {
        requireNonNull(repoName, "repoName");

        final String resourceDir = testName.getMethodName() + '/' + repoName;
        final URL resourceUrl = PluginTest.class.getResource(resourceDir);

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

        return Change.fromDirectory(f.toPath(), "/");
    }
}
