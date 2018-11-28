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

package com.linecorp.centraldogma.internal.thrift;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;

import com.google.common.base.Converter;
import com.google.common.collect.ImmutableList;

/**
 * Provides a function converting back and forth between {@link MergeQuery} and
 * {@link com.linecorp.centraldogma.common.MergeQuery}.
 */
public final class MergeQueryConverter
        extends Converter<com.linecorp.centraldogma.common.MergeQuery<?>, MergeQuery> {

    public static final Converter<com.linecorp.centraldogma.common.MergeQuery<?>, MergeQuery>
            TO_DATA = new MergeQueryConverter();

    public static final Converter<MergeQuery, com.linecorp.centraldogma.common.MergeQuery<?>>
            TO_MODEL = TO_DATA.reverse();

    @Override
    protected MergeQuery doForward(com.linecorp.centraldogma.common.MergeQuery<?> mergeQuery) {
        switch (mergeQuery.type()) {
            case IDENTITY:
                return new MergeQuery(QueryType.IDENTITY, convertMergeSources(mergeQuery), ImmutableList.of());
            case JSON_PATH:
                return new MergeQuery(QueryType.JSON_PATH, convertMergeSources(mergeQuery),
                                      mergeQuery.expressions());
        }
        throw new Error();
    }

    @Override
    protected com.linecorp.centraldogma.common.MergeQuery<?> doBackward(MergeQuery mergeQuery) {
        switch (mergeQuery.type) {
            case IDENTITY:
                return com.linecorp.centraldogma.common.MergeQuery.ofJson(convertMergeSources(mergeQuery));
            case JSON_PATH:
                return com.linecorp.centraldogma.common.MergeQuery.ofJsonPath(convertMergeSources(mergeQuery),
                                                                              mergeQuery.expressions);
        }
        throw new Error();
    }

    private static ImmutableList<MergeSource> convertMergeSources(
            com.linecorp.centraldogma.common.MergeQuery<?> mergeQuery) {
        return mergeQuery.mergeSources().stream()
                         .map(mergeSource -> new MergeSource(
                                 mergeSource.path(), mergeSource.isOptional()))
                         .collect(toImmutableList());
    }

    private static List<com.linecorp.centraldogma.common.MergeSource> convertMergeSources(
            MergeQuery mergeQuery) {
        return mergeQuery.mergeSources
                .stream()
                .map(mergeSource -> {
                    if (mergeSource.isIsOptional()) {
                        return com.linecorp.centraldogma.common.MergeSource.ofOptional(mergeSource.getPath());
                    }
                    return com.linecorp.centraldogma.common.MergeSource.ofRequired(mergeSource.getPath());
                })
                .collect(toImmutableList());
    }

    private MergeQueryConverter() {}
}
