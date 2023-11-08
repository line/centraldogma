/*
 * Copyright 2023 LINE Corporation
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

import java.util.List;

/**
 * A converter that converts a value of certain configuration properties in {@link CentralDogmaConfig}.
 * Here is the list of the properties that this converter supports:
 * <ul>
 *     <li>{@code replication.secret}</li>
 *     <li>{@code tls.keyCertChain}</li>
 *     <li>{@code tls.key}</li>
 *     <li>{@code authentication.properties.keyStore.password} (when SAML is used)</li>
 *     <li>{@code authentication.properties.keyStore.keyPasswords} (when SAML is used)</li>
 * </ul>
 * Implement this interface and register it via SPI to convert a value of the properties.
 */
public interface ConfigValueConverter {

    /**
     * Returns the list of prefixes of the properties that this converter supports.
     */
    List<String> supportedPrefixes();

    /**
     * Returns the converted value of the property. It must not return {@code null}.
     */
    String convert(String prefix, String value);
}
