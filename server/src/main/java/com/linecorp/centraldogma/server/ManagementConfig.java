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

package com.linecorp.centraldogma.server;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.SessionProtocol;
import org.jspecify.annotations.Nullable;
import com.linecorp.armeria.server.management.ManagementService;

/**
 * A configuration for the {@link ManagementService}.
 */
public final class ManagementConfig {
    private static final String DEFAULT_PROTOCOL = "http";
    private static final String DEFAULT_PATH = "/internal/management";

    private final SessionProtocol protocol;
    private final @Nullable String address;
    private final int port;
    private final String path;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public ManagementConfig(@JsonProperty("protocol") @Nullable String protocol,
                            @JsonProperty("address") @Nullable String address,
                            @JsonProperty("port") int port,
                            @JsonProperty("path") @Nullable String path) {
        this(SessionProtocol.of(firstNonNull(protocol, DEFAULT_PROTOCOL)),
             address, port, path);
    }

    /**
     * Creates a new instance.
     */
    public ManagementConfig(@Nullable SessionProtocol protocol,
                            @Nullable String address,
                            int port,
                            @Nullable String path) {
        protocol = firstNonNull(protocol, SessionProtocol.HTTP);
        checkArgument(protocol != SessionProtocol.PROXY, "protocol: %s (expected: one of %s)",
                      protocol, SessionProtocol.httpAndHttpsValues());
        this.protocol = protocol;
        this.address = address;
        checkArgument(port >= 0 && port <= 65535, "management.port: %s (expected: 0-65535)", port);
        this.port = port;
        this.path = firstNonNull(path, DEFAULT_PATH);
    }

    /**
     * Returns the protocol of the management service.
     */
    @JsonProperty("protocol")
    public SessionProtocol protocol() {
        return protocol;
    }

    /**
     * Returns the address of the management service.
     */
    @JsonProperty("address")
    public @Nullable String address() {
        return address;
    }

    /**
     * Returns the port of the management service.
     */
    @JsonProperty("port")
    public int port() {
        return port;
    }

    /**
     * Returns the path of the management service.
     */
    @JsonProperty("path")
    public String path() {
        return path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ManagementConfig)) {
            return false;
        }
        final ManagementConfig that = (ManagementConfig) o;
        return port == that.port &&
               protocol == that.protocol &&
               Objects.equals(address, that.address) &&
               path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocol, address, port, path);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("protocol", protocol)
                          .add("address", address)
                          .add("port", port)
                          .add("path", path)
                          .toString();
    }
}
