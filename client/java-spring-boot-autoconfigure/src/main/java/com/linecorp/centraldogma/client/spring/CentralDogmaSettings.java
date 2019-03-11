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

package com.linecorp.centraldogma.client.spring;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.PositiveOrZero;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.google.common.base.MoreObjects;

/**
 * Settings for a Central Dogma client.
 *
 * <h3>Example with {@code "hosts"} option</h3>
 * <pre>{@code
 * centraldogma:
 *   hosts:
 *   - replica1.examples.com:36462
 *   - replica2.examples.com:36462
 *   - replica3.examples.com:36462
 *   access-token: appToken-cffed349-d573-457f-8f74-4727ad9341ce
 *   health-check-interval-millis: 15000
 *   use-tls: false
 * }</pre>
 *
 * <h3>Example with {@code "profile"} option</h3>
 * <pre>{@code
 * centraldogma:
 *   profile: beta
 *   access-token: appToken-cffed349-d573-457f-8f74-4727ad9341ce
 *   health-check-interval-millis: 15000
 *   use-tls: false
 * }</pre>
 *
 * <p>Note that {@code "healthCheckIntervalMillis"} and {@code "useTls"} are optional.</p>
 */
@ConfigurationProperties(prefix = "centraldogma")
@Validated
public class CentralDogmaSettings {
    /**
     * The Central Dogma client profile name. If not specified, {@code "hosts"} must be specified.
     */
    @Nullable
    private String profile;

    /**
     * The list of Central Dogma hosts. e.g. {@code "foo.example.com"} or {@code "foo.example.com:36462"}.
     * If not specified, {@code "profile"} must be specified.
     */
    @Nullable
    private List<String> hosts;

    /**
     * The access token which is used to authenticate the Central Dogma client.
     */
    @Nullable
    private String accessToken;

    /**
     * The number of milliseconds between each health-check requests to Central Dogma servers.
     * {@code 0} disables the health check requests.
     */
    @Nullable
    private Long healthCheckIntervalMillis;

    /**
     * Whether to use a TLS connection when communicating with a Central Dogma server.
     */
    @Nullable
    private Boolean useTls;

    /**
     * Returns the Central Dogma client profile name.
     *
     * @return {@code null} if not specified.
     */
    @Nullable
    public String getProfile() {
        return profile;
    }

    /**
     * Sets the Central Dogma client profile name.
     */
    public void setProfile(@NotBlank String profile) {
        this.profile = profile;
    }

    /**
     * Returns the list of Central Dogma hosts.
     *
     * @return {@code null} if not specified.
     */
    @Nullable
    public List<String> getHosts() {
        return hosts;
    }

    /**
     * Sets the list of Central Dogma hosts.
     */
    public void setHosts(@NotEmpty List<String> hosts) {
        // Cannot use ImmutableList.copyOf() because Spring Boot 1.x attempts an add() call on it.
        this.hosts = new ArrayList<>(hosts);
    }

    /**
     * Returns the access token which is used to authenticate the Central Dogma client.
     *
     * @return {@code null} if not specified.
     */
    @Nullable
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * Sets the access token which is used to authenticate the Central Dogma client.
     */
    public void setAccessToken(@NotBlank String accessToken) {
        this.accessToken = accessToken;
    }

    /**
     * Returns the number of milliseconds between each health-check requests to Central Dogma servers.
     *
     * @return {@code null} if not specified.
     */
    @Nullable
    public Long getHealthCheckIntervalMillis() {
        return healthCheckIntervalMillis;
    }

    /**
     * Sets the number of milliseconds between each health-check requests to Central Dogma servers.
     */
    public void setHealthCheckIntervalMillis(@PositiveOrZero long healthCheckIntervalMillis) {
        this.healthCheckIntervalMillis = healthCheckIntervalMillis;
    }

    /**
     * Returns whether to use a TLS connection when communicating with a Central Dogma server.
     *
     * @return {@code null} if not specified.
     */
    @Nullable
    public Boolean getUseTls() {
        return useTls;
    }

    /**
     * Sets whether to use a TLS connection when communicating with a Central Dogma server.
     */
    public void setUseTls(boolean useTls) {
        this.useTls = useTls;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("profile", profile)
                          .add("hosts", hosts)
                          .add("accessToken", accessToken != null ? "********" : null)
                          .add("healthCheckIntervalMillis", healthCheckIntervalMillis)
                          .add("useTls", useTls)
                          .toString();
    }
}
