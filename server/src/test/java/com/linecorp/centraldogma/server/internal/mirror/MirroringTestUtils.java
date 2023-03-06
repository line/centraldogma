/*
 * Copyright 2022 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class MirroringTestUtils {

    static final Cron EVERY_MINUTE = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)).parse("0 * * * * ?");

    static <T extends Mirror> T newMirror(String remoteUri, Class<T> mirrorType) {
        return newMirror(remoteUri, EVERY_MINUTE, mock(Repository.class), mirrorType);
    }

    static <T extends Mirror> T newMirror(String remoteUri, Class<T> mirrorType,
                                          String projectName, String repoName) {
        final Repository repository = mock(Repository.class);
        final Project project = mock(Project.class);
        when(repository.parent()).thenReturn(project);
        when(repository.name()).thenReturn(repoName);
        when(project.name()).thenReturn(projectName);
        return newMirror(remoteUri, EVERY_MINUTE, repository, mirrorType);
    }

    static <T extends Mirror> T newMirror(String remoteUri, Cron schedule,
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

    private MirroringTestUtils() {}
}
