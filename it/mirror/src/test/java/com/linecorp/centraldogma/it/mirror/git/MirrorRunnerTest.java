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

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;

import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MirrorRunnerTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.administrators(USERNAME);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
        }
    };

    // TODO(ikhoon): Implement integration tests
}
