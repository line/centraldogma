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

package com.linecorp.centraldogma.it;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Revision;

class FileHistoryAndDiffTest {

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding();

    // getDiff

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void getHistory(ClientType clientType) {
        // TODO(trustin): Implement me.
        final CentralDogma client = clientType.client(dogma);
        System.err.println(
                client.getHistory(dogma.project(), dogma.repo1(),
                                  new Revision(1), Revision.HEAD, "/**").join());
    }
}
