/*
 * Copyright 2021 LINE Corporation
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

import org.springframework.core.Ordered;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;

/**
 * A configurator to configure the {@link ClientFactory} which is used to build the
 * Armeria Central Dogma client.
 */
public interface CentralDogmaClientFactoryConfigurator extends Ordered {

    /**
     * Configures the {@link ClientFactory} which is used to build the Armeria Central Dogma client.
     */
    void configure(ClientFactoryBuilder builder);

    @Override
    default int getOrder() {
        return 0;
    }
}
