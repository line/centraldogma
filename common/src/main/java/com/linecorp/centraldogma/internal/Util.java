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
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class borrowed some of its methods from a <a href="https://github.com/netty/netty/blob/4.1/common
 * /src/main/java/io/netty/util/NetUtil.java">NetUtil class</a> which was part of Netty project.
 */
public final class Util {

    private static final Pattern FILE_NAME_PATTERN = Pattern.compile(
            "^(?:[-_0-9a-zA-Z](?:[-_\\.0-9a-zA-Z]*[-_0-9a-zA-Z])?)+$");
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "^(?:/[-_0-9a-zA-Z](?:[-_\\.0-9a-zA-Z]*[-_0-9a-zA-Z])?)+$");
    private static final Pattern JSON_FILE_PATH_PATTERN = Pattern.compile(
            "^(?:/[-_0-9a-zA-Z](?:[-_\\.0-9a-zA-Z]*[-_0-9a-zA-Z])?)+\\.(?i)json$");
    private static final Pattern DIR_PATH_PATTERN = Pattern.compile(
            "^(?:/[-_0-9a-zA-Z](?:[-_\\.0-9a-zA-Z]*[-_0-9a-zA-Z])?)*/?$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[_A-Za-z0-9-\\+]+(?:\\.[_A-Za-z0-9-]+)*@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9]+)*(?:\\.[A-Za-z]{2,})$");
    private static final Pattern GENERAL_EMAIL_PATTERN = Pattern.compile(
            "^[_A-Za-z0-9-\\+]+(?:\\.[_A-Za-z0-9-]+)*@(.+)$");

    /**
     * Start with an alphanumeric character.
     * An alphanumeric character, minus, plus, underscore and dot are allowed in the middle.
     * End with an alphanumeric character.
     */
    private static final Pattern PROJECT_AND_REPO_NAME_PATTERN =
            Pattern.compile("^[0-9A-Za-z](?:[-+_0-9A-Za-z\\.]*[0-9A-Za-z])?$");

    public static String validateFileName(String name, String paramName) {
        requireNonNull(name, paramName);
        if (isValidFileName(name)) {
            return name;
        }

        throw new IllegalArgumentException(
                paramName + ": " + name + " (expected: " + FILE_NAME_PATTERN.pattern() + ')');
    }

    public static boolean isValidFileName(String name) {
        requireNonNull(name, "name");
        return !name.isEmpty() && FILE_NAME_PATTERN.matcher(name).matches();
    }

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

    public static String validateJsonFilePath(String path, String paramName) {
        requireNonNull(path, paramName);
        if (isValidJsonFilePath(path)) {
            return path;
        }

        throw new IllegalArgumentException(
                paramName + ": " + path + " (expected: " + JSON_FILE_PATH_PATTERN.pattern() + ')');
    }

    public static boolean isValidJsonFilePath(String path) {
        requireNonNull(path, "path");
        return !path.isEmpty() && path.charAt(0) == '/' &&
               JSON_FILE_PATH_PATTERN.matcher(path).matches();
    }

    public static String validateDirPath(String path, String paramName) {
        requireNonNull(path, paramName);
        if (isValidDirPath(path)) {
            return path;
        }

        throw new IllegalArgumentException(
                paramName + ": " + path + " (expected: " + DIR_PATH_PATTERN.pattern() + ')');
    }

    public static boolean isValidProjectName(String projectName) {
        requireNonNull(projectName, "projectName");
        return PROJECT_AND_REPO_NAME_PATTERN.matcher(projectName).matches();
    }

    public static String validateProjectName(String projectName, String paramName) {
        if (isValidProjectName(projectName)) {
            return projectName;
        }
        throw new IllegalArgumentException(paramName + ": " + projectName +
                                           " (expected: " + PROJECT_AND_REPO_NAME_PATTERN.pattern() + ')');
    }

    public static boolean isValidRepositoryName(String repoName) {
        requireNonNull(repoName, "projectName");
        return PROJECT_AND_REPO_NAME_PATTERN.matcher(repoName).matches();
    }

    public static String validateRepositoryName(String repoName, String paramName) {
        if (isValidRepositoryName(repoName)) {
            return repoName;
        }
        throw new IllegalArgumentException(paramName + ": " + repoName +
                                           " (expected: " + PROJECT_AND_REPO_NAME_PATTERN.pattern() + ')');
    }

    public static boolean isValidDirPath(String path) {
        return isValidDirPath(path, false);
    }

    public static boolean isValidDirPath(String path, boolean mustEndWithSlash) {
        requireNonNull(path);
        if (mustEndWithSlash && !path.endsWith("/")) {
            return false;
        }
        return !path.isEmpty() && path.charAt(0) == '/' &&
               DIR_PATH_PATTERN.matcher(path).matches();
    }

    public static boolean isValidEmailAddress(String emailAddr) {
        requireNonNull(emailAddr);
        if (EMAIL_PATTERN.matcher(emailAddr).matches()) {
            return true;
        }
        // Try to check whether the domain part is IP address format.
        final Matcher m = GENERAL_EMAIL_PATTERN.matcher(emailAddr);
        if (m.matches()) {
            final String domainPart = m.group(1);
            if (isValidIpV4Address(domainPart) ||
                isValidIpV6Address(domainPart)) {
                return true;
            }
        }
        return false;
    }

    public static String validateEmailAddress(String emailAddr, String paramName) {
        requireNonNull(emailAddr, paramName);
        if (isValidEmailAddress(emailAddr)) {
            return emailAddr;
        }

        throw new IllegalArgumentException(
                paramName + ": " + emailAddr +
                " (expected: " + EMAIL_PATTERN.pattern() + " or IP address domain)");
    }

    public static String toEmailAddress(String emailAddr, String paramName) {
        requireNonNull(emailAddr, paramName);
        if (isValidEmailAddress(emailAddr)) {
            return emailAddr;
        }
        return emailAddr + "@localhost.localdomain";
    }

    public static String emailToUsername(String emailAddr, String paramName) {
        validateEmailAddress(emailAddr, paramName);
        return emailAddr.substring(0, emailAddr.indexOf('@'));
    }

    public static List<String> stringToLines(String str) {
        final BufferedReader reader = new BufferedReader(new StringReader(str));
        final List<String> lines = new ArrayList<>(128);
        try {
            String line;
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

    private static boolean isValidIpV4Address(String ip) {
        return isValidIpV4Address(ip, 0, ip.length());
    }

    @SuppressWarnings("DuplicateBooleanBranch")
    private static boolean isValidIpV4Address(String ip, int from, int toExcluded) {
        int len = toExcluded - from;
        int i;
        return len <= 15 && len >= 7 &&
               (i = ip.indexOf('.', from + 1)) > 0 && isValidIpV4Word(ip, from, i) &&
               (i = ip.indexOf('.', from = i + 2)) > 0 && isValidIpV4Word(ip, from - 1, i) &&
               (i = ip.indexOf('.', from = i + 2)) > 0 && isValidIpV4Word(ip, from - 1, i) &&
               isValidIpV4Word(ip, i + 1, toExcluded);
    }

    private static boolean isValidIpV4Word(CharSequence word, int from, int toExclusive) {
        int len = toExclusive - from;
        char c0;
        char c1;
        char c2;
        if (len < 1 || len > 3 || (c0 = word.charAt(from)) < '0') {
            return false;
        }
        if (len == 3) {
            return (c1 = word.charAt(from + 1)) >= '0' &&
                   (c2 = word.charAt(from + 2)) >= '0' &&
                   (c0 <= '1' && c1 <= '9' && c2 <= '9' ||
                    c0 == '2' && c1 <= '5' && (c2 <= '5' || c1 < '5' && c2 <= '9'));
        }
        return c0 <= '9' && (len == 1 || isValidNumericChar(word.charAt(from + 1)));
    }

    private static boolean isValidIpV6Address(String ip) {
        int end = ip.length();
        if (end < 2) {
            return false;
        }

        // strip "[]"
        int start;
        char c = ip.charAt(0);
        if (c == '[') {
            end--;
            if (ip.charAt(end) != ']') {
                // must have a close ]
                return false;
            }
            start = 1;
            c = ip.charAt(1);
        } else {
            start = 0;
        }

        int colons;
        int compressBegin;
        if (c == ':') {
            // an IPv6 address can start with "::" or with a number
            if (ip.charAt(start + 1) != ':') {
                return false;
            }
            colons = 2;
            compressBegin = start;
            start += 2;
        } else {
            colons = 0;
            compressBegin = -1;
        }

        int wordLen = 0;
        loop:
        for (int i = start; i < end; i++) {
            c = ip.charAt(i);
            if (isValidHexChar(c)) {
                if (wordLen < 4) {
                    wordLen++;
                    continue;
                }
                return false;
            }

            switch (c) {
                case ':':
                    if (colons > 7) {
                        return false;
                    }
                    if (ip.charAt(i - 1) == ':') {
                        if (compressBegin >= 0) {
                            return false;
                        }
                        compressBegin = i - 1;
                    } else {
                        wordLen = 0;
                    }
                    colons++;
                    break;
                case '.':
                    // case for the last 32-bits represented as IPv4 x:x:x:x:x:x:d.d.d.d

                    // check a normal case (6 single colons)
                    if (compressBegin < 0 && colons != 6 ||
                        // a special case ::1:2:3:4:5:d.d.d.d allows 7 colons with an
                        // IPv4 ending, otherwise 7 :'s is bad
                        (colons == 7 && compressBegin >= start || colons > 7)) {
                        return false;
                    }

                    // Verify this address is of the correct structure to contain an IPv4 address.
                    // It must be IPv4-Mapped or IPv4-Compatible
                    // (see https://tools.ietf.org/html/rfc4291#section-2.5.5).
                    int ipv4Start = i - wordLen;
                    int j = ipv4Start - 2; // index of character before the previous ':'.
                    if (isValidIPv4MappedChar(ip.charAt(j))) {
                        if (!isValidIPv4MappedChar(ip.charAt(j - 1)) ||
                            !isValidIPv4MappedChar(ip.charAt(j - 2)) ||
                            !isValidIPv4MappedChar(ip.charAt(j - 3))) {
                            return false;
                        }
                        j -= 5;
                    }

                    for (; j >= start; --j) {
                        char tmpChar = ip.charAt(j);
                        if (tmpChar != '0' && tmpChar != ':') {
                            return false;
                        }
                    }

                    // 7 - is minimum IPv4 address length
                    int ipv4End = ip.indexOf('%', ipv4Start + 7);
                    if (ipv4End < 0) {
                        ipv4End = end;
                    }
                    return isValidIpV4Address(ip, ipv4Start, ipv4End);
                case '%':
                    // strip the interface name/index after the percent sign
                    end = i;
                    break loop;
                default:
                    return false;
            }
        }

        // normal case without compression
        if (compressBegin < 0) {
            return colons == 7 && wordLen > 0;
        }

        return compressBegin + 2 == end ||
               // 8 colons is valid only if compression in start or end
               wordLen > 0 && (colons < 8 || compressBegin <= start);
    }

    private static boolean isValidNumericChar(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isValidIPv4MappedChar(char c) {
        return c == 'f' || c == 'F';
    }

    private static boolean isValidHexChar(char c) {
        return c >= '0' && c <= '9' || c >= 'A' && c <= 'F' || c >= 'a' && c <= 'f';
    }

    /**
     * Deletes the specified {@code directory} recursively.
     */
    public static void deleteFileTree(File directory) throws IOException {
        if (directory.exists()) {
            Files.walkFileTree(directory.toPath(), DeletingFileVisitor.INSTANCE);
        }
    }

    private Util() {}
}
