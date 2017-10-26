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

package com.linecorp.centraldogma.server.internal.storage.repository;

import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.PATH_CREDENTIALS;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.PATH_MIRRORS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.mirror.Mirror;
import com.linecorp.centraldogma.server.internal.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.internal.mirror.credential.NoneMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

public class DefaultMetaRepositoryTest {

    private static final Change<JsonNode> UPSERT_CREDENTIALS = Change.ofJsonUpsert(
            PATH_CREDENTIALS,
            "[{" +
            "  \"type\": \"password\"," +
            "  \"hostnamePatterns\": [ \"^foo\\\\.com$\" ]," +
            "  \"username\": \"alice\"," +
            "  \"password\": \"secret_a\"" +
            "},{" +
            "  \"type\": \"password\"," +
            "  \"hostnamePatterns\": [ \"^.*\\\\.com$\" ]," +
            "  \"username\": \"bob\"," +
            "  \"password\": \"secret_b\"" +
            "}]");

    private static final CronParser cronParser = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    @ClassRule
    public static final TemporaryFolder rootDir = new TemporaryFolder();

    private static ProjectManager pm;

    @Rule
    public final TestName testName = new TestName();

    private Project project;
    private MetaRepository metaRepo;

    @BeforeClass
    public static void init() throws Exception {
        pm = new DefaultProjectManager(rootDir.getRoot(), ForkJoinPool.commonPool(), null);
    }

    @AfterClass
    public static void destroy() {
        pm.close();
    }

    @Before
    public void setUp() {
        project = pm.create(testName.getMethodName());
        project.repos().create(Project.REPO_META);
        metaRepo = project.metaRepo();
    }

    @Test
    public void testEmptyMirrors() {
        // should return an empty result when both /credentials.json and /mirrors.json are non-existent.
        assertThat(metaRepo.mirrors()).isEmpty();

        // should return an empty result when /credentials.json exists and /mirrors.json does not.
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "", Change.ofJsonUpsert("/credentials.json", "[]"));
        assertThat(metaRepo.mirrors()).isEmpty();

        // should return an empty result when both /credentials.json and /mirrors.json exist.
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "", Change.ofJsonUpsert("/mirrors.json", "[]"));
        assertThat(metaRepo.mirrors()).isEmpty();
    }

    /**
     * Ensures a {@link RepositoryMetadataException} is raised when the mirror configuration is not valid.
     */
    @Test
    public void testInvalidMirrors() {
        // not an array but an object
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert(PATH_MIRRORS, "{}")).join();
        assertThatThrownBy(() -> metaRepo.mirrors()).isInstanceOf(RepositoryMetadataException.class);

        // not an array but a value
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert(PATH_MIRRORS, "\"oops\"")).join();
        assertThatThrownBy(() -> metaRepo.mirrors()).isInstanceOf(RepositoryMetadataException.class);

        // an array that contains null.
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert(PATH_MIRRORS, "[ null ]")).join();
        assertThatThrownBy(() -> metaRepo.mirrors()).isInstanceOf(RepositoryMetadataException.class);
    }

    @Test
    public void testSingleTypeMirror() {
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert(
                                PATH_MIRRORS,
                                "[{" +
                                "  \"enabled\": true," +
                                "  \"type\": \"single\"," +
                                "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                                "  \"localRepo\": \"foo\"," +
                                "  \"localPath\": \"/mirrors/foo\"," +
                                "  \"remoteUri\": \"git+ssh://foo.com/foo.git\"" +
                                "},{" +
                                "  \"enabled\": true," +
                                "  \"type\": \"single\"," +
                                "  \"schedule\": \"*/10 * * * * ?\"," +
                                "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                "  \"localRepo\": \"bar\"," +
                                "  \"remoteUri\": \"git+ssh://bar.com/bar.git/some-path\"" +
                                "}, {" +
                                "  \"type\": \"single\"," + // Enabled implicitly
                                "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                                "  \"localRepo\": \"qux\"," +
                                "  \"remoteUri\": \"git+ssh://qux.net/qux.git#develop\"" +
                                "}, {" +
                                "  \"enabled\": false," + // Disabled
                                "  \"type\": \"single\"," +
                                "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                                "  \"localRepo\": \"foo\"," +
                                "  \"localPath\": \"/mirrors/bar\"," +
                                "  \"remoteUri\": \"git+ssh://bar.com/bar.git\"" +
                                "}]"), UPSERT_CREDENTIALS).join();

        // When the mentioned repositories (foo and bar) do not exist,
        assertThat(metaRepo.mirrors()).isEmpty();

        project.repos().create("foo");
        project.repos().create("bar");
        project.repos().create("qux");

        // Get the mirror list and sort it by localRepo name alphabetically for easier testing.
        final List<Mirror> mirrors = metaRepo.mirrors().stream()
                                             .sorted(Comparator.comparing(m -> m.localRepo().name()))
                                             .collect(Collectors.toList());

        assertThat(mirrors.stream()
                          .map(m -> m.localRepo().name())
                          .collect(Collectors.toList())).containsExactly("bar", "foo", "qux");

        final Mirror foo = mirrors.get(1);
        final Mirror bar = mirrors.get(0);
        final Mirror qux = mirrors.get(2);

        assertThat(foo.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);
        assertThat(bar.direction()).isEqualTo(MirrorDirection.REMOTE_TO_LOCAL);
        assertThat(qux.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);

        assertThat(foo.schedule().equivalent(cronParser.parse("0 * * * * ?"))).isTrue();
        assertThat(bar.schedule().equivalent(cronParser.parse("*/10 * * * * ?"))).isTrue();
        assertThat(qux.schedule().equivalent(cronParser.parse("0 * * * * ?"))).isTrue();

        assertThat(foo.localPath()).isEqualTo("/mirrors/foo/");
        assertThat(bar.localPath()).isEqualTo("/");
        assertThat(qux.localPath()).isEqualTo("/");

        assertThat(foo.remoteRepoUri().toString()).isEqualTo("git+ssh://foo.com/foo.git");
        assertThat(bar.remoteRepoUri().toString()).isEqualTo("git+ssh://bar.com/bar.git");
        assertThat(qux.remoteRepoUri().toString()).isEqualTo("git+ssh://qux.net/qux.git");

        assertThat(foo.remotePath()).isEqualTo("/");
        assertThat(bar.remotePath()).isEqualTo("/some-path/");
        assertThat(qux.remotePath()).isEqualTo("/");

        assertThat(foo.remoteBranch()).isEqualTo("master");
        assertThat(bar.remoteBranch()).isEqualTo("master");
        assertThat(qux.remoteBranch()).isEqualTo("develop");

        // Ensure the credentials are loaded correctly.

        //// Should be matched by 'alice' credential.
        assertThat(foo.credential()).isInstanceOf(PasswordMirrorCredential.class);
        //// Should be matched by 'bob' credential.
        assertThat(bar.credential()).isInstanceOf(PasswordMirrorCredential.class);
        //// Should be matched by no credential.
        assertThat(qux.credential()).isInstanceOf(NoneMirrorCredential.class);

        final PasswordMirrorCredential fooCredential = (PasswordMirrorCredential) foo.credential();
        final PasswordMirrorCredential barCredential = (PasswordMirrorCredential) bar.credential();

        assertThat(fooCredential.username()).isEqualTo("alice");
        assertThat(fooCredential.password()).isEqualTo("secret_a");
        assertThat(barCredential.username()).isEqualTo("bob");
        assertThat(barCredential.password()).isEqualTo("secret_b");
    }

    @Test
    public void testMultipleTypeMirror() {

        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert(
                                PATH_MIRRORS,
                                "[{" +
                                "  \"enabled\": true," +
                                "  \"type\": \"multiple\"," +
                                "  \"defaultDirection\": \"LOCAL_TO_REMOTE\"," +
                                "  \"defaultLocalPath\": \"/source\"," +
                                "  \"includes\": [{" +
                                "    \"pattern\": \"^([^.]+)(\\\\.[^.]+)$\"," +
                                "    \"replacement\": \"git+ssh://$1$2/$1.git#develop-$1\"" +
                                "  }]," +
                                "  \"excludes\": [ \"^qux\\\\.net$\" ]" +
                                "}, {" +
                                "  \"type\": \"multiple\"," + // Enabled implicitly.
                                "  \"defaultDirection\": \"LOCAL_TO_REMOTE\"," +
                                "  \"defaultLocalPath\": \"/source\"," +
                                "  \"defaultSchedule\": \"* * * * * ?\"," +
                                "  \"includes\": [{" +
                                "    \"pattern\": \"^qux\\\\.net$\"," +
                                "    \"replacement\": \"dogma://qux.net/origin/qux.dogma/some-path\"," +
                                "    \"schedule\": \"0 0 * * * ?\"," + // Override the defaultSchedule.
                                "    \"direction\": \"REMOTE_TO_LOCAL\"," + // Override the defaultDirection.
                                "    \"localPath\": \"/mirrored/qux\"" + // Override the defaultLocalPath.
                                "  }]" +
                                "}, {" +
                                "  \"enabled\": false," + // Disabled
                                "  \"type\": \"multiple\"," +
                                "  \"defaultDirection\": \"REMOTE_TO_LOCAL\"," +
                                "  \"defaultLocalPath\": \"/otherSource\"," +
                                "  \"includes\": [{" +
                                "    \"pattern\": \"^([^.]+)(\\\\.[^.]+)$\"," +
                                "    \"replacement\": \"git+ssh://$1$2/$1.git#others-$1\"" +
                                "  }]" +
                                "}]"), UPSERT_CREDENTIALS).join();

        // When no matching repositories exist.
        assertThat(metaRepo.mirrors()).isEmpty();

        project.repos().create("foo.com");
        project.repos().create("bar.org");
        project.repos().create("qux.net");

        // Get the mirror list and sort it by localRepo name alphabetically for easier testing.
        final List<Mirror> mirrors = metaRepo.mirrors().stream()
                                             .sorted(Comparator.comparing(m -> m.localRepo().name()))
                                             .collect(Collectors.toList());

        assertThat(mirrors.stream()
                          .map(m -> m.localRepo().name())
                          .collect(Collectors.toList())).containsExactly("bar.org", "foo.com", "qux.net");

        final Mirror foo = mirrors.get(1);
        final Mirror bar = mirrors.get(0);
        final Mirror qux = mirrors.get(2);

        // Ensure the directions are generated correctly.
        assertThat(foo.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);
        assertThat(bar.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);
        assertThat(qux.direction()).isEqualTo(MirrorDirection.REMOTE_TO_LOCAL);

        // Ensure the schedules are generated correctly.
        assertThat(foo.schedule().equivalent(cronParser.parse("0 * * * * ?"))).isTrue();
        assertThat(bar.schedule().equivalent(cronParser.parse("0 * * * * ?"))).isTrue();
        assertThat(qux.schedule().equivalent(cronParser.parse("0 0 * * * ?"))).isTrue();

        // Ensure the localPaths are generated correctly.
        assertThat(foo.localPath()).isEqualTo("/source/");
        assertThat(bar.localPath()).isEqualTo("/source/");
        assertThat(qux.localPath()).isEqualTo("/mirrored/qux/");

        // Ensure the remoteUris are generated correctly.
        assertThat(foo.remoteRepoUri().toASCIIString()).isEqualTo("git+ssh://foo.com/foo.git");
        assertThat(bar.remoteRepoUri().toASCIIString()).isEqualTo("git+ssh://bar.org/bar.git");
        assertThat(qux.remoteRepoUri().toASCIIString()).isEqualTo("dogma://qux.net/origin/qux.dogma");

        // Ensure the remotePaths are generated correctly.
        assertThat(foo.remotePath()).isEqualTo("/");
        assertThat(bar.remotePath()).isEqualTo("/");
        assertThat(qux.remotePath()).isEqualTo("/some-path/");

        // Ensure the remoteBranches are generated correctly.
        assertThat(foo.remoteBranch()).isEqualTo("develop-foo");
        assertThat(bar.remoteBranch()).isEqualTo("develop-bar");
        assertThat(qux.remoteBranch()).isNull(); // Central Dogma has no notion of branch.

        // Ensure the direction is set correctly.
        assertThat(foo.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);
        assertThat(bar.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);
        assertThat(qux.direction()).isEqualTo(MirrorDirection.REMOTE_TO_LOCAL);
    }
}
