/*
 * Copyright 2017 LINE Corporation
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

import static com.linecorp.centraldogma.internal.thrift.ErrorCode.REVISION_NOT_FOUND;
import static com.linecorp.centraldogma.testing.internal.ExpectedExceptionAppender.assertThatThrownByWithExpectedException;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaException;
import com.linecorp.centraldogma.server.internal.storage.repository.RevisionNotFoundException;

public class RevisionNormalizationTest {

    @ClassRule
    public static final CentralDogmaRuleWithScaffolding rule = new CentralDogmaRuleWithScaffolding();

    /**
     * Ensure that the absolute major revision numbers are returned as-is.
     */
    @Test
    public void testAbsoluteMajor() throws Exception {
        final Revision expected = new Revision(1);
        final Revision actual = rule.client().normalizeRevision(rule.project(), rule.repo1(), expected).join();
        assertThat(actual).isEqualTo(expected);
    }

    /**
     * Ensure that the out-of-range absolute major revision numbers are rejected.
     */
    @Test
    public void testAbsoluteMajorOutOfRange() throws Exception {
        final Revision outOfRange = new Revision(Integer.MAX_VALUE);
        assertThatThrownByWithExpectedException(RevisionNotFoundException.class, "2147483647", () ->
                rule.client().normalizeRevision(rule.project(), rule.repo1(), outOfRange).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == REVISION_NOT_FOUND);
    }

    @Test
    public void testRelativeMajor() throws Exception {
        final Revision actual = rule.client().normalizeRevision(
                rule.project(), rule.repo1(), Revision.HEAD).join();
        assertThat(actual.isRelative()).isFalse();
    }

    /**
     * Ensure that the out-of-range relative major revision numbers are rejected.
     */
    @Test
    public void testRelativeMajorOutOfRange() throws Exception {
        final Revision outOfRange = new Revision(Integer.MIN_VALUE);
        assertThatThrownByWithExpectedException(RevisionNotFoundException.class, "-2147483648", () ->
                rule.client().normalizeRevision(rule.project(), rule.repo1(), outOfRange).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == REVISION_NOT_FOUND);
    }
}
