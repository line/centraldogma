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

package com.linecorp.centraldogma.client.armeria.legacy;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Converter;

import com.linecorp.centraldogma.client.CommitAndChanges;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.internal.thrift.ChangeConverter;
import com.linecorp.centraldogma.internal.thrift.Commit;
import com.linecorp.centraldogma.internal.thrift.CommitConverter;

/**
 * Provides a function converting back and forth between {@link Commit} and
 * {@link CommitAndChanges}.
 */
final class CommitAndChangesConverter extends Converter<CommitAndChanges<?>, Commit> {

    static final Converter<CommitAndChanges<?>, Commit> TO_DATA = new CommitAndChangesConverter();

    static final Converter<Commit, CommitAndChanges<?>> TO_MODEL = TO_DATA.reverse();

    private CommitAndChangesConverter() {
    }

    @Override
    protected Commit doForward(CommitAndChanges<?> commitAndChanges) {
        Commit converted = CommitConverter.TO_DATA.convert(commitAndChanges.commit());
        return converted.setDiffs(commitAndChanges.changes().stream()
                                                  .map(ChangeConverter.TO_DATA::convert)
                                                  .collect(toImmutableList()));
    }

    @Override
    protected CommitAndChanges<?> doBackward(Commit commit) {
        return new CommitAndChanges<>(
                CommitConverter.TO_MODEL.convert(commit),
                commit.getDiffs().stream()
                      .map(change -> {
                          @SuppressWarnings("unchecked")
                          Change<Object> converted = (Change<Object>) ChangeConverter.TO_MODEL.convert(change);
                          return converted;
                      })
                      .collect(toImmutableList()));
    }
}
