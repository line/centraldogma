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
package com.linecorp.centraldogma.server.auth.saml;

import static java.util.Objects.requireNonNull;

final class HtmlUtil {

    private static final String BEGIN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"DTD/xhtml1-strict.dtd\">" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\" lang=\"en\">" +
            "<head><meta http-equiv=\"content-type\" content=\"text/html;charset=utf-8\" /></head>" +
            "<body onload=\"";

    private static final String END = "\"></body></html>";

    static String getHtmlWithOnload(String... statements) {
        requireNonNull(statements, "statements");

        final StringBuilder sb = new StringBuilder(BEGIN);
        for (String statement : statements) {
            sb.append(statement).append(';');
        }
        sb.append(END);
        return sb.toString();
    }

    private HtmlUtil() {}
}
