/*
 * Copyright 2025 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CredentialUtil {

    // TODO(minwoox): remove ._ from the ID which violates Google AIP.
    public static final Pattern PROJECT_CREDENTIAL_ID_PATTERN =
            Pattern.compile("^projects/([^/]+)/credentials/([a-z](?:[a-z0-9-_.]{0,61}[a-z0-9])?)$");

    // TODO(minwoox): remove ._ from the ID.
    public static final Pattern REPO_CREDENTIAL_ID_PATTERN =
            Pattern.compile(
                    "^projects/([^/]+)/repos/([^/]+)/credentials/([a-z](?:[a-z0-9-_.]{0,61}[a-z0-9])?)$");

    public static String validateCredentialName(String projectName, String repoName,
                                                String credentialName) {
        requireNonNull(credentialName, "credentialName");
        if (credentialName.isEmpty()) {
            // Allow an empty credential ID for Credential.FALLBACK.
            return "";
        }

        Matcher matcher = PROJECT_CREDENTIAL_ID_PATTERN.matcher(credentialName);
        if (matcher.matches()) {
            checkProjectName(projectName, matcher);
            return credentialName;
        }

        matcher = REPO_CREDENTIAL_ID_PATTERN.matcher(credentialName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid credentialName: " + credentialName +
                                               " (expected: " + PROJECT_CREDENTIAL_ID_PATTERN.pattern() +
                                               " or " + REPO_CREDENTIAL_ID_PATTERN.pattern() + ')');
        }
        checkProjectAndRepoName(projectName, repoName, matcher);
        return credentialName;
    }

    public static void validateProjectCredentialName(String projectName,
                                                     String credentialName) {
        final Matcher matcher = PROJECT_CREDENTIAL_ID_PATTERN.matcher(credentialName);
        checkArgument(matcher.matches(),
                      "invalid project credentialName: %s (expected: %s)",
                      credentialName, PROJECT_CREDENTIAL_ID_PATTERN.pattern());
        checkProjectName(projectName, matcher);
    }

    public static void validateRepoCredentialName(String projectName,
                                                  String repoName,
                                                  String credentialName) {
        final Matcher matcher = REPO_CREDENTIAL_ID_PATTERN.matcher(credentialName);
        checkArgument(matcher.matches(),
                      "invalid repository credentialName: %s (expected: %s)",
                      credentialName, REPO_CREDENTIAL_ID_PATTERN.pattern());
        checkProjectAndRepoName(projectName, repoName, matcher);
    }

    private static void checkProjectAndRepoName(String projectName, String repoName, Matcher matcher) {
        checkProjectName(projectName, matcher);
        final String repoNameGroup = matcher.group(2);
        if (!repoName.equals(repoNameGroup)) {
            throw new IllegalArgumentException("repoName and credentialName do not match: " +
                                               repoName + " vs " + repoNameGroup);
        }
    }

    private static void checkProjectName(String projectName, Matcher matcher) {
        final String projectNameGroup = matcher.group(1);
        if (!projectName.equals(projectNameGroup)) {
            throw new IllegalArgumentException("projectName and credentialName do not match: " +
                                               projectName + " vs " + projectNameGroup);
        }
    }

    public static String credentialName(String projectName, String credentialId) {
        return "projects/" + projectName + "/credentials/" + credentialId;
    }

    public static String credentialName(String projectName, String repoName, String credentialId) {
        return "projects/" + projectName + "/repos/" + repoName + "/credentials/" + credentialId;
    }

    public static String credentialFile(String credentialName) {
        // Strip the project name. e.g. "projects/foo/credentials/bar" -> "/credentials/bar.json"
        final int index = credentialName.indexOf('/', 9); // The length of "projects/" is 9.
        return credentialName.substring(index) + ".json";
    }

    public static String projectCredentialFile(String credentialId) {
        return "/credentials/" + credentialId + ".json";
    }

    public static String repoCredentialFile(String repoName, String credentialId) {
        return "/repos/" + repoName + "/credentials/" + credentialId + ".json";
    }

    public static String requireNonEmpty(String value, String name) {
        requireNonNull(value, name);
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is empty.");
        }
        return value;
    }

    private CredentialUtil() {}
}
