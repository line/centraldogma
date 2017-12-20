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

package com.linecorp.centraldogma.internal;

import static com.linecorp.centraldogma.internal.Util.validateDirPath;
import static com.linecorp.centraldogma.internal.Util.validateEmailAddress;
import static com.linecorp.centraldogma.internal.Util.validateFilePath;
import static com.linecorp.centraldogma.internal.Util.validateJsonFilePath;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class UtilTest {

    @Test
    public void testValidateFilePath() {
        assertFilePathValidationSuccess("/foo.txt");
        assertFilePathValidationSuccess("/foo/bar.txt");
        assertFilePathValidationSuccess("/foo.bar/baz.json");
        assertFilePathValidationSuccess("/foo-bar/baz-json");

        // No relative path
        assertFilePathValidationFailure("foo");

        // No directory
        assertFilePathValidationFailure("/");
        assertFilePathValidationFailure("/foo/");

        // No double slashes
        assertFilePathValidationFailure("//");
        assertFilePathValidationFailure("/foo//bar");

        // No leading or trailing dots
        assertFilePathValidationFailure("/.");
        assertFilePathValidationFailure("/..");
        assertFilePathValidationFailure("/.foo");
        assertFilePathValidationFailure("/foo.");
        assertFilePathValidationFailure("/.foo.");

        // a-z, 0-9, minus, dot and underscore only
        assertFilePathValidationFailure("/\t");
        assertFilePathValidationFailure("/80:20");
        assertFilePathValidationFailure("/foo*.txt");
        assertFilePathValidationFailure("/bar?.txt");
        assertFilePathValidationFailure("/baz|.txt");
        assertFilePathValidationFailure("/\uAC00\uB098\uB2E4.json"); // 가나다
    }

    @Test
    public void testValidateJsonFilePath() {
        assertJsonFilePathValidationSuccess("/foo.json");
        assertJsonFilePathValidationSuccess("/foo/bar.json");
        assertJsonFilePathValidationSuccess("/foo.bar/baz.json");

        // case-insensitive
        assertJsonFilePathValidationSuccess("/foo.JSON");
        assertJsonFilePathValidationSuccess("/foo.Json");
        assertJsonFilePathValidationSuccess("/foo.jsoN");

        // Invalid extensions
        assertJsonFilePathValidationFailure("/foo.txt");
        assertJsonFilePathValidationFailure("/foo/bar.txt");
        assertJsonFilePathValidationFailure("/foo.bar/baz.json.txt");
        assertJsonFilePathValidationFailure("/foo-bar/baz-json");

        // No directory
        assertJsonFilePathValidationFailure("/");
        assertJsonFilePathValidationFailure("/foo/");

        // No leading or trailing dots
        assertJsonFilePathValidationFailure("/.");
        assertJsonFilePathValidationFailure("/..");
        assertJsonFilePathValidationFailure("/.json");
        assertJsonFilePathValidationFailure("/json.");
        assertJsonFilePathValidationFailure("/.json.");

        // a-z, 0-9, minus, dot and underscore only
        assertJsonFilePathValidationFailure("/\t");
        assertJsonFilePathValidationFailure("/80:20");
        assertJsonFilePathValidationFailure("/foo*.json");
        assertJsonFilePathValidationFailure("/bar?.json");
        assertJsonFilePathValidationFailure("/baz|.json");
        assertJsonFilePathValidationFailure("/\uAC00\uB098\uB2E4.json"); // 가나다
    }

    @Test
    public void testValidateDirPath() {
        assertDirPathValidationSuccess("/");
        assertDirPathValidationSuccess("/foo");
        assertDirPathValidationSuccess("/foo/");
        assertDirPathValidationSuccess("/foo/bar");
        assertDirPathValidationSuccess("/foo/bar/");
        assertDirPathValidationSuccess("/foo.bar/");
        assertDirPathValidationSuccess("/foo-bar/");

        // No relative path
        assertDirPathValidationFailure("foo");

        // No double slashes
        assertDirPathValidationFailure("//");
        assertDirPathValidationFailure("/foo//bar");

        // No leading or trailing dots
        assertDirPathValidationFailure("/./");
        assertDirPathValidationFailure("/../");
        assertDirPathValidationFailure("/.foo/");
        assertDirPathValidationFailure("/foo./");
        assertDirPathValidationFailure("/.foo./");

        // a-z, 0-9, minus, dot and underscore only
        assertDirPathValidationFailure("/\t/");
        assertDirPathValidationFailure("/80:20/");
        assertDirPathValidationFailure("/foo*/");
        assertDirPathValidationFailure("/bar?/");
        assertDirPathValidationFailure("/baz|/");
        assertDirPathValidationFailure("/\uAC00\uB098\uB2E4/"); // 가나다
    }

    @Test
    public void testValidEmailAddress() {
        testValidEmail("dogma@github.com");
        testValidEmail("dogma@127.0.0.1");
        testValidEmail("dogma@10.1.1.1");
        testValidEmail("dogma@0:0:0:0:0:0:0:1");
        testValidEmail("dogma@::1");
        testValidEmail("dogma@2001:0db8:85a3:0000:0000:8a2e:0370:7334");
        testValidEmail("dogma@2001:db8:85a3:0:0:8a2e:370:7334");
        testValidEmail("dogma@2001:db8:85a3::8a2e:370:7334");

        testInvalidEmail("dogma!@github.com");
        testInvalidEmail("dogma@127.0.0.256");
        testInvalidEmail("dogma@10.1.1");
        testInvalidEmail("dogma@0:0:0:0:0:0:0:0:1");
        testInvalidEmail("dogma@:::1");
        testInvalidEmail("dogma@2001:0db8:85a3:0000:0000:8a2e:0370:733X");
        testInvalidEmail("dogma@2001:db8:85a3:00:8a2e:370:7334");
        testInvalidEmail("dogma@2001:db8:85a38a2e:370:7334");
    }

    private static void assertFilePathValidationSuccess(String path) {
        validateFilePath(path, "path");
    }

    private static void assertFilePathValidationFailure(String path) {
        assertThatThrownBy(() -> validateFilePath(path, "path"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    private static void assertJsonFilePathValidationSuccess(String path) {
        validateJsonFilePath(path, "path");
    }

    private static void assertJsonFilePathValidationFailure(String path) {
        assertThatThrownBy(() -> validateJsonFilePath(path, "path"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    private static void assertDirPathValidationSuccess(String path) {
        validateDirPath(path, "path");
    }

    private static void assertDirPathValidationFailure(String path) {
        assertThatThrownBy(() -> validateDirPath(path, "path"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    private static void testValidEmail(String email) {
        validateEmailAddress(email, "email");
    }

    private static void testInvalidEmail(String invalidEmail) {
        assertThatThrownBy(() -> testValidEmail(invalidEmail))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }
}
