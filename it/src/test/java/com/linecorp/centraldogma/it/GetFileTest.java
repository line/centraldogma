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

import static com.linecorp.centraldogma.testing.internal.ExpectedExceptionAppender.assertThatThrownByWithExpectedException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryExecutionException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;

class GetFileTest {

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding();

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void invalidJsonPath(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        assertThatThrownBy(() -> client.getFile(
                dogma.project(), dogma.repo1(), Revision.HEAD,
                Query.ofJsonPath("/test/test2.json", "$.non_exist_path")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(QueryExecutionException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void invalidFile(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        assertThatThrownByWithExpectedException(EntryNotFoundException.class, "non_existing_file", () ->
                client.getFile(dogma.project(), dogma.repo1(), Revision.HEAD,
                               Query.ofJsonPath("/test/non_existing_file.json", "$.a")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(EntryNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void invalidRepo(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        assertThatThrownByWithExpectedException(RepositoryNotFoundException.class, "non_exist_repo", () ->
                client.getFile(dogma.project(), "non_exist_repo", Revision.HEAD,
                               Query.ofJsonPath("/test/test2.json", "$.a")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(RepositoryNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void invalidProject(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        assertThatThrownByWithExpectedException(ProjectNotFoundException.class, "non_exist_proj", () ->
                client.getFile("non_exist_proj", dogma.repo1(), Revision.HEAD,
                               Query.ofJsonPath("/test/test2.json", "$.non_exist_path")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(ProjectNotFoundException.class);
    }
}
