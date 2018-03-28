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

package com.linecorp.centraldogma.client.armeria;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.centraldogma.client.CentralDogma;

/**
 * Configures the underlying <a href="https://line.github.io/armeria/">Armeria</a> client of
 * {@link CentralDogma}. Can be used to register arbitrary client decorators. e.g.
 *
 * <pre>{@code
 * CentralDogmaBuilder builder = new CentralDogmaBuilder();
 * builder.clientConfigurator(cb -> {
 *     // Collect the client-side metrics under the meter name 'dogma.client'
 *     cb.decorator(HttpRequest.class, HttpResponse.class,
 *                  MetricCollectingClient.newDecorator(
 *                          MeterIdFunction.ofDefault("dogma.client")));
 * });
 * ...
 * }</pre>
 */
@FunctionalInterface
public interface ArmeriaClientConfigurator {
    /**
     * Configures the client using the specified {@link ClientBuilder}.
     */
    void configure(ClientBuilder cb);
}
