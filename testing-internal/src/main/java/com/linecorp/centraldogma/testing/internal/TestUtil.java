/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.centraldogma.testing.internal;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOError;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.centraldogma.internal.Jackson;

public final class TestUtil {

    private static final Pattern DISALLOWED_CHARS = Pattern.compile("[^a-zA-Z0-9]");

    public static <T> void assertJsonConversion(T value, String json) {
        @SuppressWarnings("unchecked")
        final Class<T> valueType = (Class<T>) value.getClass();
        assertJsonConversion(value, valueType, json);
    }

    public static <T> void assertJsonConversion(T value, Class<T> valueType, String json) {
        assertThatJson(json).isEqualTo(value);
        try {
            assertThat(Jackson.readValue(json, valueType)).isEqualTo(value);
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static String normalizedDisplayName(TestInfo testInfo) {
        return normalizedDisplayName(testInfo.getDisplayName(), testInfo.getTestMethod());
    }

    public static String normalizedDisplayName(ExtensionContext context) {
        return normalizedDisplayName(context.getDisplayName(), context.getTestMethod());
    }

    private static String normalizedDisplayName(String displayName, Optional<Method> method) {
        return DISALLOWED_CHARS.matcher(displayName + method.map(Method::getName).orElse(""))
                               .replaceAll("");
    }

    private TestUtil() {}
}
