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

package com.linecorp.centraldogma.server.internal.api;

/**
 * The outcome of a repository recovery request.
 */
public enum RecoveryStatus {
    /**
     * The request landed on the source replica, which originated the recovery. The other replicas apply
     * it when they replay it from the replication log, so this does not mean the cluster has converged.
     */
    COMPLETED,
    /**
     * The source replica has been asked over the replication log to originate the recovery
     * asynchronously, best-effort: a failure is only reported in the source replica's log.
     */
    REQUESTED
}
