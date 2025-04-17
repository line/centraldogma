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
package com.linecorp.centraldogma.client.armeria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.InvalidPushException;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ArmeriaCentralDogmaTest {

    @RegisterExtension
    static CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
        }
    };

    @Test
    void pushFileToDogmaRepositoryShouldFail() throws UnknownHostException {
        final CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .build();

        assertThatThrownBy(() -> client.forRepo("foo", Project.REPO_META)
                                       .commit("summary", Change.ofJsonUpsert("/bar.json", "{ \"a\": \"b\" }"))
                                       .push()
                                       .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(InvalidPushException.class);
    }

    @Test
    void pushMirrorsJsonFileToDogmaRepository() throws UnknownHostException {
        final CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .build();

        final PushResult result = client.forRepo("foo", Project.REPO_META)
                                        .commit("summary",
                                                Change.ofJsonUpsert("/repos/foo/mirrors/foo.json", "{}"))
                                        .push()
                                        .join();
        assertThat(result.revision().major()).isPositive();
    }
}
