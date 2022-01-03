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

package com.linecorp.centraldogma.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.InvalidPushException;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

public class MetaRepositoryPushTest {

    @RegisterExtension
    static CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
        }
    };

    @Test
    void pushFileToMetaRepositoryShouldFail() throws UnknownHostException {
        final CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .build();

        assertThatThrownBy(() -> client.push("foo",
                                             "meta",
                                             Revision.HEAD,
                                             "summary",
                                             Change.ofJsonUpsert("/bar.json", "{ \"a\": \"b\" }"))
                                       .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(InvalidPushException.class);
    }

    @Test
    void pushMirrorsJsonFileToMetaRepository() throws UnknownHostException {
        final CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .build();

        final PushResult result = client.push("foo",
                                              "meta",
                                              Revision.HEAD,
                                              "summary",
                                              Change.ofJsonUpsert("/mirrors.json", "[]"))
                                        .join();
        assertThat(result.revision().major()).isPositive();
    }
}
