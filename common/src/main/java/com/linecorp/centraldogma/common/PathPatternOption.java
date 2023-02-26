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

import java.util.function.Function;

/**
 * A {@link PathPatternBuilder} option.
 */
public class PathPatternOption {
    private final String name;
    /**
     * Precedence is the priority  of an option relative to others.
     * Precedence level 1 is the highest precedence level, followed by level 2 v.v...
     * In other words, n has a higher precedence than n+ 1.
     */
    private final Integer precedence;

    private final String pattern;
    /**
     * Create {@link PathPattern} from {@code pattern}.
     */
    private final Function<String, PathPattern> pathPatternCreator;

    PathPatternOption(int precedence, String name, String pattern,
                      Function<String, PathPattern> pathPatternCreator) {
        this.precedence = precedence;
        this.name = name;
        this.pattern = pattern;
        this.pathPatternCreator = pathPatternCreator;
    }

    /**
     * Returns the option name.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the precedence level of the option.
     */
    public int getPrecedence() {
        return precedence;
    }

    /**
     * Returns the {@link PathPattern} of the option.
     */
    public PathPattern getPathPattern() {
        return pathPatternCreator.apply(pattern);
    }
}
