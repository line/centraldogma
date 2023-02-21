package com.linecorp.centraldogma.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class PathPatternBuilderTest {

    @Test
    void testSingleOption() {
        assertThat(PathPattern.startsWith("/foo/bar")
                              .patternString()).isEqualTo("/foo/bar/**");
        assertThat(PathPattern.endsWith("json")
                              .patternString()).isEqualTo("/**/json");
        assertThat(PathPattern.contains("/bar")
                              .patternString()).isEqualTo("/**/bar/**");
        assertThat(PathPattern.extension("json")
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
                              .startsWith("/foo/bar")
                              .startsWith("/override")
                              .extension("json")
                              .build()
                              .patternString()).isEqualTo("/override/**/*.json");

        assertThat(PathPattern.builder()
                              .startsWith("/foo")
                              .contains("/bar/")
                              .extension("json")
                              .build()
                              .patternString()).isEqualTo("/foo/**/bar/**/*.json");
    }
}
