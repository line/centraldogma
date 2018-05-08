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
package com.linecorp.centraldogma.client.armeria;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.Endpoint;

/**
 * Decodes the content of a file in Central Dogma into a list of {@link Endpoint}s.
 *
 * @param <T> the type of the file in Central Dogma
 */
@FunctionalInterface
public interface EndpointListDecoder<T> {

    /**
     * Default {@link EndpointListDecoder} implementation for {@link JsonNode}.
     * Retrieved object must be a JSON array (which has format as {@code "[\"segment1\", \"segment2\"]"})
     * Each segment represents an endpoint whose format is
     * {@code <host>[:<port_number>[:weight]]}, such as:
     * <ul>
     * <li>{@code "foo.com"} - default port number, default weight (1000)</li>
     * <li>{@code "bar.com:8080} - port number 8080, default weight (1000)</li>
     * <li>{@code "10.0.2.15:0:500} - default port number, weight 500</li>
     * <li>{@code "192.168.1.2:8443:700} - port number 8443, weight 700</li>
     * </ul>
     * Note that the port number must be specified when you want to specify the weight.
     */
    EndpointListDecoder<JsonNode> JSON = new JsonEndpointListDecoder();

    /**
     * Default {@link EndpointListDecoder} implementation for {@link String}.
     * Retrieved object must be a string which is a list of segments separated by a newline character.
     * Each segment represents an endpoint whose format is
     * {@code <host>[:<port_number>[:weight]]}, such as:
     * <ul>
     * <li>{@code "foo.com"} - default port number, default weight (1000)</li>
     * <li>{@code "bar.com:8080} - port number 8080, default weight (1000)</li>
     * <li>{@code "10.0.2.15:0:500} - default port number, weight 500</li>
     * <li>{@code "192.168.1.2:8443:700} - port number 8443, weight 700</li>
     * </ul>
     * Note that the port number must be specified when you want to specify the weight.
     */
    EndpointListDecoder<String> TEXT = new TextEndpointListDecoder();

    /**
     * Decodes an object into a set of {@link Endpoint}s.
     *
     * @param object an object retrieved from Central Dogma.
     * @return the list of {@link Endpoint}s
     */
    List<Endpoint> decode(T object);
}
