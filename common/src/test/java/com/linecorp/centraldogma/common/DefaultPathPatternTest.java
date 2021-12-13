package com.linecorp.centraldogma.common;

import static com.linecorp.centraldogma.common.DefaultPathPattern.encodePathPattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

class DefaultPathPatternTest {

    @Test
    void pathPattern() {
        DefaultPathPattern pathPattern = new DefaultPathPattern(
                ImmutableSet.of("/foo/*.json",
                                "/*/foo.txt",
                                "*.json")); // /**/ is prepended when the path does not start with /

        assertThat(pathPattern.get()).isEqualTo("/foo/*.json,/*/foo.txt,/**/*.json");

        pathPattern = new DefaultPathPattern(ImmutableSet.of("/foo/*.json",
                                                             "/*/foo.txt",
                                                             "/**"));
        assertThat(pathPattern.get()).isEqualTo("/**");
    }

    @Test
    void invalidPathPattern() {
        assertThatThrownBy(() -> new DefaultPathPattern(ImmutableSet.of("/,foo/*.json")))
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