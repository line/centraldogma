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

package com.linecorp.centraldogma.testing.internal;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.junit.Assert.assertEquals;

import java.io.IOError;
import java.io.IOException;

import com.linecorp.centraldogma.internal.Jackson;

public final class TestUtil {

    public static <T> void assertJsonConversion(T value, String json) {
        @SuppressWarnings("unchecked")
        final Class<T> valueType = (Class<T>) value.getClass();
        assertJsonConversion(value, valueType, json);
    }

    public static <T> void assertJsonConversion(T value, Class<T> valueType, String json) {
        assertThatJson(json).isEqualTo(value);
        try {
            assertEquals(value, Jackson.readValue(json, valueType));
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    private TestUtil() {}
}
