/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.centraldogma.common;

import static com.linecorp.centraldogma.common.Revision.HEAD;
import static com.linecorp.centraldogma.common.Revision.INIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class RevisionRangeTest {

    @Test
    public void revisionRange() {
        RevisionRange range = new RevisionRange(2, 4);

        assertThat(range.isAscending()).isTrue();
        assertThat(range.isRelative()).isFalse();
        assertThat(range.toAscending()).isSameAs(range);
        assertThat(range.toDescending()).isEqualTo(new RevisionRange(4, 2));

        final Revision revisionTen = new Revision(10);
        range = new RevisionRange(revisionTen, INIT);

        assertThat(range.isAscending()).isFalse();
        assertThat(range.isRelative()).isFalse();
        assertThat(range.toAscending()).isEqualTo(new RevisionRange(INIT, revisionTen));
        assertThat(range.toDescending()).isSameAs(range);

        range = new RevisionRange(revisionTen, revisionTen);

        assertThat(range.isAscending()).isFalse();
        assertThat(range.isRelative()).isFalse();
        assertThat(range.toAscending()).isSameAs(range);
        assertThat(range.toDescending()).isSameAs(range);

        final Revision revisionNegativeThree = new Revision(-3);
        final Revision revisionNegativeTen = new Revision(-10);
        range = new RevisionRange(revisionNegativeTen, revisionNegativeThree);

        assertThat(range.isAscending()).isTrue();
        assertThat(range.isRelative()).isTrue();
        assertThat(range.toAscending()).isSameAs(range);
        assertThat(range.toDescending()).isEqualTo(
                new RevisionRange(revisionNegativeThree, revisionNegativeTen));

        final RevisionRange relativeRange = new RevisionRange(INIT, HEAD);

        assertThat(relativeRange.isRelative()).isTrue();
        assertThat(relativeRange.from()).isSameAs(INIT);
        assertThat(relativeRange.to()).isSameAs(HEAD);
        assertThatThrownBy(relativeRange::isAscending)
                .isExactlyInstanceOf(IllegalStateException.class);
        assertThatThrownBy(relativeRange::toAscending)
                .isExactlyInstanceOf(IllegalStateException.class);
        assertThatThrownBy(relativeRange::toDescending)
                .isExactlyInstanceOf(IllegalStateException.class);
    }
}
