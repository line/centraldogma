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

package com.linecorp.centraldogma.client;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.internal.Jackson;

final class ClientProfile {

    private final String name;
    private final int priority;
    private final Set<Entry> hosts;

    @JsonCreator
    ClientProfile(@JsonProperty(value = "name", required = true) String name,
                  @JsonProperty("priority") @Nullable Integer priority,
                  @JsonProperty("hosts") @JsonDeserialize(contentAs = Entry.class) @Nullable Set<Entry> hosts) {
        this.name = requireNonNull(name, "name");
        checkArgument(!name.isEmpty(), "name is empty.");
        this.priority = firstNonNull(priority, 0);
        this.hosts = ImmutableSet.copyOf(firstNonNull(hosts, ImmutableSet.of()));
    }

    @JsonProperty
    String name() {
        return name;
    }

    @JsonProperty
    int priority() {
        return priority;
    }

    @JsonProperty
    Set<Entry> hosts() {
        return hosts;
    }

    @Override
    public String toString() {
        try {
            return Jackson.writeValueAsPrettyString(this);
        } catch (JsonProcessingException e) {
            // Should never reach here.
            throw new Error(e);
        }
    }

    static final class Entry {
        private final String host;
        private final String protocol;
        private final int port;

        @JsonCreator
        Entry(@JsonProperty(value = "host", required = true) String host,
              @JsonProperty(value = "protocol", required = true) String protocol,
              @JsonProperty(value = "port", required = true) Integer port) {
            this.host = requireNonNull(host, "host");
            checkArgument(!host.isEmpty(), "hostname is empty.");
            this.protocol = requireNonNull(protocol, "protocol");
            checkArgument(!protocol.isEmpty(), "protocol is empty.");
            this.port = requireNonNull(port, "port");
            checkArgument(port > 0 && port < 65536, "port: %s (expected: 1..65535)", port);
        }

        @JsonProperty
        String host() {
            return host;
        }

        @JsonProperty
        String protocol() {
            return protocol;
        }

        @JsonProperty
        int port() {
            return port;
        }

        @Override
        public String toString() {
            try {
                return Jackson.writeValueAsPrettyString(this);
            } catch (JsonProcessingException e) {
                // Should never reach here.
                throw new Error(e);
            }
        }
    }
}
