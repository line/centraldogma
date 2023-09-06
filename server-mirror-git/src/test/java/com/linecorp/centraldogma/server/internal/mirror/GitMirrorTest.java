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
package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.server.internal.mirror.MirroringTestUtils.EVERY_MINUTE;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringTestUtils.assertMirrorNull;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringTestUtils.newMirror;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.ZonedDateTime;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.cronutils.model.Cron;

import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

class GitMirrorTest {

    @Test
    void testGitMirror() {
        // Simplest possible form
        assertMirror("git://a.com/b.git", GitMirror.class,
                     "git://a.com/b.git", "/", null);

        // Non-default port number
        assertMirror("git+ssh://a.com:8022/b.git", GitMirror.class,
                     "git+ssh://a.com:8022/b.git", "/", null);

        // Non-default remotePath
        assertMirror("git+http://a.com/b.git/c", GitMirror.class,
                     "git+http://a.com/b.git", "/c/", null);

        // Non-default remoteBranch
        assertMirror("git+https://a.com/b.git#develop", GitMirror.class,
                     "git+https://a.com/b.git", "/", "develop");

        // Non-default remotePath and remoteBranch
        assertMirror("git+ssh://a.com/b.git/c#develop", GitMirror.class,
                     "git+ssh://a.com/b.git", "/c/", "develop");

        // remoteUri must contain the '.git' suffix.
        assertThatThrownBy(() -> newMirror("git://a.com/b", GitMirror.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newMirror("git://a.com/b.dogma", GitMirror.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testUnknownScheme() {
        assertMirrorNull("magma://a.com/b.magma");
        assertMirrorNull("git+foo://a.com/b.git");
    }

    @Test
    void jitter() {
        final AbstractMirror mirror = assertMirror("git://a.com/b.git", AbstractMirror.class,
                                                   "git://a.com/b.git", "/", null);

        assertThat(mirror.schedule()).isSameAs(EVERY_MINUTE);

        // When jitter is less then the configured interval.
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:20:00+00:00"), 1000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:20:01+00:00"));
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:20:01+00:00"), 1000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:21:01+00:00"));

        // When jitter is equal to the configured interval.
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:20:00+00:00"), 60000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:21:00+00:00"));
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:21:30+00:00"), 60000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:22:00+00:00"));

        // When jitter is more than the configured interval.
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:20:00+00:00"), 90000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:20:30+00:00"));
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:20:30+00:00"), 90000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:21:30+00:00"));
    }

    private static <T extends Mirror> T assertMirror(String remoteUri, Class<T> mirrorType,
                                                     String expectedRemoteRepoUri,
                                                     String expectedRemotePath,
                                                     @Nullable String expectedRemoteBranch) {
        final Repository repository = mock(Repository.class);
        final Project project = mock(Project.class);
        when(repository.parent()).thenReturn(project);
        when(repository.name()).thenReturn("bar");
        when(project.name()).thenReturn("foo");

        final T m = newMirror(remoteUri, EVERY_MINUTE, repository, mirrorType);
        assertThat(m.remoteRepoUri().toString()).isEqualTo(expectedRemoteRepoUri);
        assertThat(m.remotePath()).isEqualTo(expectedRemotePath);
        assertThat(m.remoteBranch()).isEqualTo(expectedRemoteBranch);
        return m;
    }

    private static <T extends Mirror> T newMirror(String remoteUri, Class<T> mirrorType) {
        return newMirror(remoteUri, EVERY_MINUTE, mock(Repository.class), mirrorType);
    }

    private static <T extends Mirror> T newMirror(String remoteUri, Cron schedule,
                                                  Repository repository, Class<T> mirrorType) {
        final MirrorCredential credential = mock(MirrorCredential.class);
        final Mirror mirror = Mirror.of("my-mirror-0", schedule, MirrorDirection.LOCAL_TO_REMOTE,
                                        credential, repository, "/", URI.create(remoteUri), null, true);

        assertThat(mirror).isInstanceOf(mirrorType);
        assertThat(mirror.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);
        assertThat(mirror.credential()).isSameAs(credential);
        assertThat(mirror.localRepo()).isSameAs(repository);
        assertThat(mirror.localPath()).isEqualTo("/");

        @SuppressWarnings("unchecked")
        final T castMirror = (T) mirror;
        return castMirror;
    }
}
