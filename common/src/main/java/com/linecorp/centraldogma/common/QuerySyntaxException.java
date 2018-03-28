/*
 * Copyright 2018 LINE Corporation
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

/**
 * An {@link IllegalArgumentException} that is raised when the syntax validation of a {@link Query} has failed.
 */
public class QuerySyntaxException extends IllegalArgumentException {

    private static final long serialVersionUID = 5098760016210421003L;

    /**
     * Creates a new instance.
     */
    public QuerySyntaxException() {}

    /**
     * Creates a new instance.
     */
    public QuerySyntaxException(String message) {
        super(message);
    }

    /**
     * Creates a new instance.
     */
    public QuerySyntaxException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance.
     */
    public QuerySyntaxException(Throwable cause) {
        super(cause);
    }
}
