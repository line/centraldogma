/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.centraldogma.it.mirror.git;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.InvalidPushException;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class LegacyGitMirrorSettingsTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
        }
    };

    @Test
    void shouldNotAllowLegacyMirrorSettings() {
        final CentralDogma client = dogma.client();
        assertThatThrownBy(() -> {
            client.forRepo("foo", Project.REPO_META)
                  .commit("Add /mirrors.json",
                          Change.ofJsonUpsert("/mirrors.json",
                                              "[{" +
                                              "  \"id\": \"foo\"," +
                                              "  \"enabled\": true," +
                                              "  \"type\": \"single\"," +
                                              "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                              "  \"localRepo\": \"local\"," +
                                              "  \"localPath\": \"localPath0\"," +
                                              "  \"remoteUri\": \"remoteUri\"," +
                                              "  \"schedule\": \"0 0 0 1 1 ? 2099\"" +
                                              "}]"))
                  .push().join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(InvalidPushException.class)
          .hasMessageContaining(" The '/mirrors.json' file is disallowed.");

        assertThatThrownBy(() -> {
            client.forRepo("foo", Project.REPO_META)
                  .commit("Add /mirrors.json",
                          Change.ofJsonUpsert("/mirrors.json",
                                              "[{" +
                                              "  \"id\": \"foo\"," +
                                              "  \"enabled\": true," +
                                              "  \"type\": \"single\"," +
                                              "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                              "  \"localRepo\": \"local\"," +
                                              "  \"localPath\": \"localPath0\"," +
                                              "  \"remoteUri\": \"remoteUri\"," +
                                              "  \"schedule\": \"0 0 0 1 1 ? 2099\"" +
                                              "}]"))
                  .push().join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(InvalidPushException.class)
          .hasMessageContaining("The '/mirrors.json' file is disallowed.");

        assertThatThrownBy(() -> {
            client.forRepo("foo", Project.REPO_META)
                  .commit("Add /credentials.json",
                          Change.ofJsonUpsert("/credentials.json",
                                              "[{" +
                                              "  \"id\": \"access-token-id\"" +
                                              "}]"))
                  .push().join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(InvalidPushException.class)
          .hasMessageContaining("The '/credentials.json' file is disallowed.");
    }
}
