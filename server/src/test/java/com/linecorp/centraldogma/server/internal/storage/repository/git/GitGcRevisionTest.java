/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.centraldogma.common.Revision;

public class GitGcRevisionTest {

    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
    }

    @Test
    void readAndWrite() throws IOException {
        final File rootDir = Files.createTempDirectory(tempDir, null).toFile();

        try (GitGcRevision gitGcRevision = new GitGcRevision(rootDir)) {
            assertThat(gitGcRevision.lastRevision()).isNull();
            final Revision revision = new Revision(10);
            gitGcRevision.write(revision);
            assertThat(gitGcRevision.lastRevision()).isSameAs(revision);

            // Should not overwrite the last revision with the old one
            final Revision oldRevision = new Revision(9);
            gitGcRevision.write(oldRevision);
            assertThat(gitGcRevision.lastRevision()).isSameAs(revision);

            // Should not overwrite with the new revision
            final Revision newRevision = new Revision(11);
            gitGcRevision.write(newRevision);
            assertThat(gitGcRevision.lastRevision()).isSameAs(newRevision);
        }
    }
}
