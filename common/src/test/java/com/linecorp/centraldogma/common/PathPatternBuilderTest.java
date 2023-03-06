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
package com.linecorp.centraldogma.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatNoException;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PathPatternBuilderTest {
    @Test
    void testExtensionPattern() {
        assertThatNoException().isThrownBy(() -> PathPatternOptions.EXTENSION.apply(".JPG").pathPattern());
        assertThatNoException().isThrownBy(() -> PathPatternOptions.EXTENSION.apply(".7z").pathPattern());

        assertThatThrownBy(() -> PathPatternOptions.EXTENSION.apply("가txt").pathPattern())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathPatternOptions.EXTENSION.apply("..tx.t").pathPattern())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSingleOption() {
        assertThat(PathPattern.builder().startsWith("/foo/bar").build()
                              .patternString()).isEqualTo("/foo/bar/**");
        assertThat(PathPattern.builder().endsWith("foo.json").build()
                              .patternString()).isEqualTo("/**/foo.json");
        assertThat(PathPattern.builder().contains("/bar").build()
                              .patternString()).isEqualTo("/**/bar/**");
        assertThat(PathPattern.builder().hasExtension("json").build()
                              .patternString()).isEqualTo("/**/*.json");
    }

    @Test
    void testPathPatternBuilder() {
        assertThat(PathPattern.builder()
                              .startsWith("/foo/bar")
                              .endsWith("foo.txt")
                              .build()
                              .patternString()).isEqualTo("/foo/bar/**/foo.txt");
        assertThat(PathPattern.builder()
                              .startsWith("/foo")
                              .contains("/bar/")
                              .hasExtension("json")
                              .build()
                              .patternString()).isEqualTo("/foo/**/bar/**/*.json");

        assertThat(PathPattern.builder()
                              .startsWith("/foo")
                              .endsWith("qux.json")
                              .hasExtension("json")
                              .build()
                              .patternString()).isEqualTo("/foo/**/*.json");
        assertThat(PathPattern.builder()
                              .startsWith("/foo")
                              .hasExtension("json")
                              .endsWith("qux.json")
                              .build()
                              .patternString()).isEqualTo("/foo/**/qux.json");

        assertThat(PathPattern.builder()
                              .contains("/foo")
                              .contains("/bar")
                              .build()
                              .patternString()).isEqualTo("/**/foo/**/bar/**");
        assertThat(PathPattern.builder()
                              .startsWith("/foo/bar")
                              .startsWith("/override")
                              .hasExtension("json")
                              .build()
                              .patternString()).isEqualTo("/override/**/*.json");
    }
}
