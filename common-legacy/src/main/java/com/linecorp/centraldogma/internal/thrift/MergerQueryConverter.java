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
 * Provides a function converting back and forth between {@link MergerQuery} and
 * {@link com.linecorp.centraldogma.common.MergerQuery}.
 */
public final class MergerQueryConverter
        extends Converter<com.linecorp.centraldogma.common.MergerQuery<?>, MergerQuery> {

    public static final Converter<com.linecorp.centraldogma.common.MergerQuery<?>, MergerQuery>
            TO_DATA = new MergerQueryConverter();

    public static final Converter<MergerQuery, com.linecorp.centraldogma.common.MergerQuery<?>>
            TO_MODEL = TO_DATA.reverse();

    @Override
    protected MergerQuery doForward(
            com.linecorp.centraldogma.common.MergerQuery<?> mergerQuery) {
        switch (mergerQuery.type()) {
            case JSON_MERGER:
                return new MergerQuery(null,
                                       convertPathAndOptionals(mergerQuery),
                                       mergerQuery.expressions());
        }
        throw new Error();
    }

    @Override
    protected com.linecorp.centraldogma.common.MergerQuery<?> doBackward(
            MergerQuery mergerQuery) {
        switch (mergerQuery.type) {
            case JSON_MERGER:
                return com.linecorp.centraldogma.common.MergerQuery.ofJsonPath(
                        convertPathAndOptionals(mergerQuery), mergerQuery.expressions);
        }
        throw new Error();
    }

    private ImmutableList<PathAndOptional> convertPathAndOptionals(
            com.linecorp.centraldogma.common.MergerQuery<?> mergerQuery) {
        return mergerQuery.pathAndOptionals().stream()
                          .map(pathAndOptional -> new PathAndOptional(
                                  pathAndOptional.getPath(), pathAndOptional.isOptional()))
                          .collect(toImmutableList());
    }

    private List<com.linecorp.centraldogma.common.PathAndOptional> convertPathAndOptionals(
            MergerQuery mergerQuery) {
        return mergerQuery.pathAndOptionals
                .stream()
                .map(pathAndOptional -> new com.linecorp.centraldogma.common.PathAndOptional(
                        pathAndOptional.getPath(),
                        pathAndOptional.isIsOptional()))
                .collect(toImmutableList());
    }

    private MergerQueryConverter() {}
}
