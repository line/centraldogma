/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Revision;

public final class CrudContext {

    private final String projectName;
    private final String repoName;
    private final String targetPath;
    private final Revision revision;

    public CrudContext(String projectName, String repoName, String targetPath, Revision revision) {
        this.projectName = requireNonNull(projectName, "projectName");
        this.repoName = requireNonNull(repoName, "repoName");
        checkArgument(targetPath.startsWith("/"), "targetPath: %s (expected: starts with '/')", targetPath);
        checkArgument(targetPath.endsWith("/"), "targetPath: %s (expected: ends with '/')", targetPath);
        this.targetPath = requireNonNull(targetPath, "targetPath");
        this.revision = requireNonNull(revision, "revision");
    }

    public String projectName() {
        return projectName;
    }

    public String repoName() {
        return repoName;
    }

    public String targetPath() {
        return targetPath;
    }

    public Revision revision() {
        return revision;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CrudContext)) {
            return false;
        }
        final CrudContext that = (CrudContext) o;
        return projectName.equals(that.projectName) && repoName.equals(that.repoName) &&
               targetPath.equals(that.targetPath) && revision.equals(revision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectName, repoName, targetPath, revision);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("projectName", projectName)
                          .add("repoName", repoName)
                          .add("targetPath", targetPath)
                          .add("revision", revision)
                          .toString();
    }
}
