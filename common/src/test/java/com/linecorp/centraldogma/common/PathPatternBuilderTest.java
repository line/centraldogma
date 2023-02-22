package com.linecorp.centraldogma.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class PathPatternBuilderTest {

    @Test
    void testSingleOption() {
        assertThat(PathPattern.builder().startsWith("/foo/bar").build()
                              .patternString()).isEqualTo("/foo/bar/**");
        assertThat(PathPattern.builder().endsWith("json").build()
                              .patternString()).isEqualTo("/**/json");
        assertThat(PathPattern.builder().contains("/bar").build()
                              .patternString()).isEqualTo("/**/bar/**");
        assertThat(PathPattern.builder().extension("json").build()
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
                              .extension("json")
                              .build()
                              .patternString()).isEqualTo("/foo/**/bar/**/*.json");

        assertThat(PathPattern.builder()
                              .startsWith("/foo")
                              .endsWith("qux.json")
                              .extension("json")
                              .build()
                              .patternString()).isEqualTo("/foo/**/*.json");
        assertThat(PathPattern.builder()
                              .startsWith("/foo")
                              .extension("json")
                              .endsWith("qux.json")
                              .build()
                              .patternString()).isEqualTo("/foo/**/qux.json");
    }

    @Test
    void testInvalidPathPatternBuilder() {
        assertThatThrownBy(() -> PathPattern.builder()
                                            .startsWith("/foo/bar")
                                            .startsWith("/override")
                                            .extension("json")
                                            .build()
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
