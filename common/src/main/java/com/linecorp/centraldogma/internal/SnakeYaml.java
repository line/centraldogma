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

package com.linecorp.centraldogma.internal;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;

public final class SnakeYaml {
    private static final Yaml yaml = new Yaml();

    public static final Node nullNode = yaml.represent(null);

    public static <T> T readValue(String data, Class<T> type) {
        return yaml.loadAs(data, type);
    }

    public static Node readTree(String data) {
        return yaml.compose(new StringReader(data));
    }

    public static Node readTree(byte[] data) {
        return yaml.compose(new InputStreamReader(new ByteArrayInputStream(data)));
    }

    public static String serialize(Node data) {
        final StringWriter stringWriter = new StringWriter();
        yaml.serialize(data, stringWriter);
        return stringWriter.toString();
    }

    private SnakeYaml() {}
}
