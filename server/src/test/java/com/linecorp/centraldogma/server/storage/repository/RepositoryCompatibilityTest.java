/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Revision;

class RepositoryCompatibilityTest {

    @Test
    void recoveryCapabilitiesAreDefaultMethods() throws Exception {
        assertDefaultMethod(Repository.class, "head");
        assertDefaultMethod(Repository.class, "cacheGeneration");
        assertDefaultMethod(Repository.class, "lastRecoveryRevision");
        assertDefaultMethod(RepositoryManager.class, "recoverRepository",
                            String.class, Revision.class, List.class);
        assertDefaultMethod(RepositoryManager.class, "buildRecoveryPayload",
                            String.class, Revision.class, Revision.class);
    }

    @Test
    void repositoryRecoveryCapabilitiesHaveDefaults() {
        final Repository repository = mock(Repository.class, CALLS_REAL_METHODS);

        assertThat(repository.cacheGeneration()).isZero();
        assertThat(repository.lastRecoveryRevision()).isEqualTo(Revision.INIT);
        assertThatThrownBy(repository::head).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void repositoryManagerRecoveryCapabilitiesHaveDefaults() {
        final RepositoryManager repositories = mock(RepositoryManager.class, CALLS_REAL_METHODS);

        assertThatThrownBy(() -> repositories.recoverRepository("repo", Revision.INIT,
                                                                 ImmutableList.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repositories.buildRecoveryPayload("repo", new Revision(2),
                                                                    new Revision(3)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertDefaultMethod(Class<?> type, String name,
                                            Class<?>... parameterTypes) throws Exception {
        assertThat(type.getMethod(name, parameterTypes).isDefault()).isTrue();
    }
}
