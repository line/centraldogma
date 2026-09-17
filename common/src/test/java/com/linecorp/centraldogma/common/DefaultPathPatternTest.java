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

import static com.linecorp.centraldogma.common.DefaultPathPattern.encodePathPattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

class DefaultPathPatternTest {

    @Test
    void pathPattern() {
        PathPattern pathPattern = PathPattern.of(
                ImmutableSet.of("/foo/*.json",
                                "/*/ foo.txt",
                                "*.json")); // /**/ is prepended when the path does not start with /

        assertThat(pathPattern.patternString()).isEqualTo("/foo/*.json,/*/ foo.txt,/**/*.json");
        assertThat(pathPattern.encoded()).isEqualTo("/foo/*.json,/*/%20foo.txt,/**/*.json");

        pathPattern = PathPattern.of(ImmutableSet.of("/foo/*.json", "/*/foo.txt", "/**"));
        assertThat(pathPattern.patternString()).isEqualTo("/**");
        assertThat(pathPattern.encoded()).isEqualTo("/**");
    }

    @Test
    void invalidPathPattern() {
        assertThatThrownBy(() -> new DefaultPathPattern(ImmutableSet.of("/,foo/*.json")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofExtension() {
        assertThat(PathPattern.ofExtension("json").patternString()).isEqualTo("/**/*.json");
        assertThat(PathPattern.ofExtension(".json").patternString()).isEqualTo("/**/*.json");
    }

    @Test
    void ofExtensionRejectsNonAlphanumeric() {
        assertThatThrownBy(() -> PathPattern.ofExtension("/foo/bar"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathPattern.ofExtension("json.gz"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathPattern.ofExtension(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathPattern.ofExtension("."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startsWith() {
        assertThat(PathPattern.startsWith("/foo/bar").patternString()).isEqualTo("/foo/bar**");
        // A leading slash is added automatically so the prefix is anchored at the root.
        assertThat(PathPattern.startsWith("foo/bar").patternString()).isEqualTo("/foo/bar**");
        // The match is not restricted to complete path segments.
        assertThat(PathPattern.startsWith("/foo/ba").patternString()).isEqualTo("/foo/ba**");
    }

    @Test
    void startsWithRejectsWildcard() {
        assertThatThrownBy(() -> PathPattern.startsWith("/foo/*"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startsWithRejectsEmpty() {
        assertThatThrownBy(() -> PathPattern.startsWith(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void under() {
        assertThat(PathPattern.under("/foo/bar").patternString()).isEqualTo("/foo/bar/**");
        assertThat(PathPattern.under("/foo/bar/").patternString()).isEqualTo("/foo/bar/**");
        // A leading slash is added automatically so the directory is anchored at the root.
        assertThat(PathPattern.under("foo/bar").patternString()).isEqualTo("/foo/bar/**");
    }

    @Test
    void underRejectsWildcard() {
        assertThatThrownBy(() -> PathPattern.under("/a/**"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void underRejectsEmpty() {
        assertThatThrownBy(() -> PathPattern.under(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testEncodePathPattern() {
        assertThat(encodePathPattern("/")).isEqualTo("/");
        assertThat(encodePathPattern(" ")).isEqualTo("%20");
        assertThat(encodePathPattern("  ")).isEqualTo("%20%20");
        assertThat(encodePathPattern("a b")).isEqualTo("a%20b");
        assertThat(encodePathPattern(" a ")).isEqualTo("%20a%20");

        // No new string has to be created when escaping is not necessary.
        final String pathPatternThatDoesNotNeedEscaping = "/*.zip,/**/*.jar";
        assertThat(encodePathPattern(pathPatternThatDoesNotNeedEscaping))
                .isSameAs(pathPatternThatDoesNotNeedEscaping);
    }
}
