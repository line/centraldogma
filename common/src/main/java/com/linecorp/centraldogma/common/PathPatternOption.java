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
final class PathPatternOption {
    private final String pattern;
    /**
     * Create {@link PathPattern} from {@code pattern}.
     */
    private final Function<String, PathPattern> pathPatternCreator;

    PathPatternOption(String pattern,
                      Function<String, PathPattern> pathPatternCreator) {
        this.pattern = pattern;
        this.pathPatternCreator = pathPatternCreator;
    }

    /**
     * Returns the {@link PathPattern} of the option.
     */
    PathPattern pathPattern() {
        return pathPatternCreator.apply(pattern);
    }
}