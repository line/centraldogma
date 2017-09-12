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

import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

public final class Util {

    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "^(?:/[-_0-9a-zA-Z](?:[-_\\.0-9a-zA-Z]*[-_0-9a-zA-Z])?)+$");
    private static final Pattern DIR_PATH_PATTERN = Pattern.compile(
            "^(?:/[-_0-9a-zA-Z](?:[-_\\.0-9a-zA-Z]*[-_0-9a-zA-Z])?)*/?$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[_A-Za-z0-9-\\+]+(?:\\.[_A-Za-z0-9-]+)*@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9]+)*(?:\\.[A-Za-z]{2,})$");

    public static String validateFilePath(String path, String paramName) {
        requireNonNull(path, paramName);
        if (isValidFilePath(path)) {
            return path;
        }

        throw new IllegalArgumentException(
                paramName + ": " + path + " (expected: " + FILE_PATH_PATTERN.pattern() + ')');
    }

    public static boolean isValidFilePath(String path) {
        requireNonNull(path, "path");
        return !path.isEmpty() && path.charAt(0) == '/' &&
               FILE_PATH_PATTERN.matcher(path).matches();
    }

    public static String validateDirPath(String path, String paramName) {
        requireNonNull(path, paramName);
        if (isValidDirPath(path)) {
            return path;
        }

        throw new IllegalArgumentException(
                paramName + ": " + path + " (expected: " + DIR_PATH_PATTERN.pattern() + ')');
    }

    public static boolean isValidDirPath(String path) {
        requireNonNull(path);
        return !path.isEmpty() && path.charAt(0) == '/' &&
               DIR_PATH_PATTERN.matcher(path).matches();
    }

    public static boolean isValidEmailAddress(String emailAddr) {
        requireNonNull(emailAddr);
        return EMAIL_PATTERN.matcher(emailAddr).matches();
    }

    public static String validateEmailAddress(String emailAddr, String paramName) {
        requireNonNull(emailAddr, paramName);
        if (isValidEmailAddress(emailAddr)) {
            return emailAddr;
        }

        throw new IllegalArgumentException(
                paramName + ": " + emailAddr + " (expected: " + EMAIL_PATTERN.pattern() + ')');
    }

    public static List<String> stringToLines(String str) {
        BufferedReader reader = new BufferedReader(new StringReader(str));
        List<String> lines = new LinkedList<>();
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException ignored) {
            // Should never happen.
        }
        return lines;
    }

    /**
     * Returns the simplified name of the type of the specified object.
     */
    public static String simpleTypeName(Object obj) {
        if (obj == null) {
            return "null";
        }

        return simpleTypeName(obj.getClass(), false);
    }

    /**
     * Returns the simplified name of the specified type.
     */
    public static String simpleTypeName(Class<?> clazz) {
        return simpleTypeName(clazz, false);
    }

    /**
     * Returns the simplified and (optionally) decapitalized name of the specified type.
     */
    public static String simpleTypeName(Class<?> clazz, boolean decapitalize) {
        if (clazz == null) {
            return "null";
        }

        String className = clazz.getName();
        final int lastDotIdx = className.lastIndexOf('.');
        if (lastDotIdx >= 0) {
            className = className.substring(lastDotIdx + 1);
        }

        if (!decapitalize) {
            return className;
        }

        StringBuilder buf = new StringBuilder(className.length());
        boolean lowercase = true;
        for (int i = 0; i < className.length(); i++) {
            char c1 = className.charAt(i);
            char c2;
            if (lowercase) {
                c2 = Character.toLowerCase(c1);
                if (c1 == c2) {
                    lowercase = false;
                }
            } else {
                c2 = c1;
            }
            buf.append(c2);
        }

        return buf.toString();
    }

    /**
     * Casts an object unsafely. Used when you want to suppress the unchecked type warnings.
     */
    @SuppressWarnings("unchecked")
    public static <T> T unsafeCast(Object o) {
        return (T) o;
    }

    /**
     * Makes sure the specified {@code values} and all its elements are not {@code null}.
     */
    public static <T> Iterable<T> requireNonNullElements(Iterable<T> values, String name) {
        requireNonNull(values, name);

        int i = 0;
        for (T v : values) {
            if (v == null) {
                throw new NullPointerException(name + '[' + i + ']');
            }
            i++;
        }

        return values;
    }

    private Util() {}
}
