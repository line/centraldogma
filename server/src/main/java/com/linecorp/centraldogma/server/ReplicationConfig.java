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

package com.linecorp.centraldogma.server;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Replication settings.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "method")
@JsonSubTypes({
        @Type(value = NoneReplicationConfig.class, name = "NONE"),
        @Type(value = ZooKeeperReplicationConfig.class, name = "ZOOKEEPER"),
})
@SuppressWarnings("InterfaceMayBeAnnotatedFunctional")
public interface ReplicationConfig {

    /**
     * Disables replication.
     */
    ReplicationConfig NONE = NoneReplicationConfig.instance();

    /**
     * Returns the desired replication method.
     */
    ReplicationMethod method();
}
