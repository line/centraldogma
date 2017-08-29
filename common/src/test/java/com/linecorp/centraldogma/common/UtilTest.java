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

package com.linecorp.centraldogma.common;

import static com.linecorp.centraldogma.common.Util.validateDirPath;
import static com.linecorp.centraldogma.common.Util.validateFilePath;
import static org.junit.Assert.fail;

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

    private static void assertFilePathValidationSuccess(String path) {
        validateFilePath(path, "path");
    }

    private static void assertDirPathValidationSuccess(String path) {
        validateDirPath(path, "path");
    }

    private static void assertFilePathValidationFailure(String path) {
        try {
            validateFilePath(path, "path");
            fail();
        } catch (IllegalArgumentException ignored) {
            // Expected
        }
    }

    private static void assertDirPathValidationFailure(String path) {
        try {
            validateDirPath(path, "path");
            fail();
        } catch (IllegalArgumentException ignored) {
            // Expected
        }
    }
}
