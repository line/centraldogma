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
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;

class RevisionNormalizationTest {

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding();

    /**
     * Ensure that the absolute major revision numbers are returned as-is.
     */
    @ParameterizedTest
    @EnumSource(ClientType.class)
    void absoluteMajor(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final Revision expected = new Revision(1);
        final Revision actual = client.normalizeRevision(dogma.project(), dogma.repo1(), expected).join();
        assertThat(actual).isEqualTo(expected);
    }

    /**
     * Ensure that the out-of-range absolute major revision numbers are rejected.
     */
    @ParameterizedTest
    @EnumSource(ClientType.class)
    void absoluteMajorOutOfRange(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        final Revision outOfRange = new Revision(Integer.MAX_VALUE);
        assertThatThrownByWithExpectedException(RevisionNotFoundException.class, "2147483647", () ->
                client.normalizeRevision(dogma.project(), dogma.repo1(), outOfRange).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(RevisionNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void relativeMajor(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final Revision actual = client.normalizeRevision(
                dogma.project(), dogma.repo1(), Revision.HEAD).join();
        assertThat(actual.isRelative()).isFalse();
    }

    /**
     * Ensure that the out-of-range relative major revision numbers are rejected.
     */
    @ParameterizedTest
    @EnumSource(ClientType.class)
    void relativeMajorOutOfRange(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        final Revision outOfRange = new Revision(Integer.MIN_VALUE);
        assertThatThrownByWithExpectedException(RevisionNotFoundException.class, "-2147483648", () ->
                client.normalizeRevision(dogma.project(), dogma.repo1(), outOfRange).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(RevisionNotFoundException.class);
    }
}
