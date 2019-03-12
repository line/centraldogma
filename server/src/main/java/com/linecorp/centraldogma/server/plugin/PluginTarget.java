/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.server.plugin;

/**
 * Targets that a {@link Plugin} is applied to which replica.
 */
public enum PluginTarget {
    /**
     * Run the {@link Plugin} on the all replicas. It would be started after the replica is started
     * and would be stopped before the replica is stopped.
     */
    ALL_REPLICAS,
    /**
     * Run the {@link Plugin} on the leader replica. It would be started after the replica has taken
     * the leadership and would be stopped when the replica is about to release the leadership.
     */
    LEADER_ONLY
}
