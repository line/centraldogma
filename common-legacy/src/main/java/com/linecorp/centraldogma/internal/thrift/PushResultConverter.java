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

import java.time.Instant;

import com.google.common.base.Converter;

import com.linecorp.centraldogma.common.PushResult;

/**
 * Provides a function converting back and forth between {@link Commit} and {@link PushResult}.
 */
public final class PushResultConverter extends Converter<PushResult, Commit> {

    public static final Converter<Commit, PushResult> TO_MODEL = new PushResultConverter().reverse();

    private PushResultConverter() {}

    @Override
    protected Commit doForward(PushResult pushResult) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected PushResult doBackward(Commit commit) {
        return new PushResult(RevisionConverter.TO_MODEL.convert(commit.getRevision()),
                              Instant.parse(commit.getTimestamp()).toEpochMilli());
    }
}
