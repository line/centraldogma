/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class LastFileRevisionTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
        }

        @Override
        protected void configureClient(ArmeriaCentralDogmaBuilder builder) {
            builder.clientConfigurator(cb -> cb.responseTimeoutMillis(0));
        }

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.requestTimeoutMillis(0);
        }
    };

    @Test
    void test() {
        final CentralDogmaRepository repo = dogma.client().forRepo("foo", "bar");
        final PushResult resultA = repo.commit("add a file", Change.ofTextUpsert("/a.txt", "aaa"))
                                       .push().join();
        final PushResult resultB = repo.commit("add a file", Change.ofTextUpsert("/b.txt", "bbb"))
                                       .push().join();
        // add /a/c.txt
        final PushResult resultC = repo.commit("add a file", Change.ofTextUpsert("/a/c.txt", "ccc")).push()
                                       .join();
        // add /a/c/d.txt
        final PushResult resultD = repo.commit("add a file", Change.ofTextUpsert("/a/c/d.txt", "ddd")).push()
                                       .join();
        // add /a/c/d/e.txt
        final PushResult resultE = repo.commit("add a file", Change.ofTextUpsert("/a/c/d/e.txt", "eee")).push()
                                       .join();

        final Map<String, Entry<?>> filesWithLastRevision = repo.file(PathPattern.all())
                                                                .includeLastFileRevision(10)
                                                                .list().join();
        assertThat(filesWithLastRevision.get("/a.txt").revision().major()).isEqualTo(2);
        final Map<String, Entry<?>> content = repo.file(PathPattern.all()).get().join();
        final Map<String, Entry<?>> contentWithLastRevision = repo.file(PathPattern.all())
                                                                  .includeLastFileRevision(10)
                                                                  .get().join();
    }
}
