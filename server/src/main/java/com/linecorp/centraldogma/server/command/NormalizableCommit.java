/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.server.command;

/**
 * A {@link Command} that can be transformed to a {@link PushAsIsCommand} via {@link #asIs(CommitResult)}.
 */
@FunctionalInterface
public interface NormalizableCommit {

    /**
     * Returns a new {@link PushAsIsCommand} which is converted using {@link CommitResult}
     * for replicating to other replicas.
     */
    PushAsIsCommand asIs(CommitResult commitResult);
}
