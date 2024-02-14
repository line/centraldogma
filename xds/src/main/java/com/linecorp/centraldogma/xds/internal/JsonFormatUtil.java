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

package com.linecorp.centraldogma.xds.internal;

import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Parser;
import com.google.protobuf.util.JsonFormat.Printer;
import com.google.protobuf.util.JsonFormat.TypeRegistry;

import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

final class JsonFormatUtil {

    private static final Printer PRINTER;
    private static final Parser PARSER;

    static {
        final TypeRegistry typeRegistry = TypeRegistry.newBuilder()
                                                      .add(HttpConnectionManager.getDescriptor())
                                                      .add(Router.getDescriptor())
                                                      .build();
        PRINTER = JsonFormat.printer().usingTypeRegistry(typeRegistry);
        PARSER = JsonFormat.parser().usingTypeRegistry(typeRegistry);
    }

    static Printer printer() {
        return PRINTER;
    }

    static Parser parser() {
        return PARSER;
    }

    private JsonFormatUtil() {}
}
