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

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;

public class GitGcRevisionTest {

    @RegisterExtension
    final TemporaryFolderExtension rootDir = new TemporaryFolderExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void readAndWrite() throws IOException {
        try (GitGcRevision gitGcRevision = new GitGcRevision(rootDir.newFolder().toFile())) {

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
