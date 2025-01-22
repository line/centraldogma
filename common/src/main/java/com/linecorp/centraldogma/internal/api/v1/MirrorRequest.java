/*
 * Copyright 2023 LINE Corporation
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
 *
 */

package com.linecorp.centraldogma.internal.api.v1;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.centraldogma.internal.CredentialUtil.validateCredentialResourceName;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MirrorRequest {

    private final String id;
    private final boolean enabled;
    private final String projectName;
    @Nullable
    private final String schedule;
    private final String direction;
    private final String localRepo;
    private final String localPath;
    private final String remoteScheme;
    private final String remoteUrl;
    private final String remotePath;
    private final String remoteBranch;
    @Nullable
    private final String gitignore;
    private final String credentialResourceName;
    @Nullable
    private final String zone;

    @JsonCreator
    public MirrorRequest(@JsonProperty("id") String id,
                         @JsonProperty("enabled") @Nullable Boolean enabled,
                         @JsonProperty("projectName") String projectName,
                         @JsonProperty("schedule") @Nullable String schedule,
                         @JsonProperty("direction") String direction,
                         @JsonProperty("localRepo") String localRepo,
                         @JsonProperty("localPath") String localPath,
                         @JsonProperty("remoteScheme") String remoteScheme,
                         @JsonProperty("remoteUrl") String remoteUrl,
                         @JsonProperty("remotePath") String remotePath,
                         @JsonProperty("remoteBranch") String remoteBranch,
                         @JsonProperty("gitignore") @Nullable String gitignore,
                         // TODO(minwoox): Remove this credentialId property after migration is done.
                         @JsonProperty("credentialId") @Nullable String credentialId,
                         @JsonProperty("credentialResourceName") @Nullable String credentialResourceName,
                         @JsonProperty("zone") @Nullable String zone) {
        this.id = requireNonNull(id, "id");
        this.enabled = firstNonNull(enabled, true);
        this.projectName = requireNonNull(projectName, "projectName");
        this.schedule = schedule;
        this.direction = requireNonNull(direction, "direction");
        this.localRepo = requireNonNull(localRepo, "localRepo");
        this.localPath = requireNonNull(localPath, "localPath");
        this.remoteScheme = requireNonNull(remoteScheme, "remoteScheme");
        this.remoteUrl = requireNonNull(remoteUrl, "remoteUrl");
        this.remotePath = requireNonNull(remotePath, "remotePath");
        this.remoteBranch = requireNonNull(remoteBranch, "remoteBranch");
        this.gitignore = gitignore;
        this.credentialResourceName = requireNonNull(firstNonNull(credentialResourceName, credentialId),
                                                     "credentialResourceName");
        validateCredentialResourceName(projectName, localRepo, this.credentialResourceName);
        this.zone = zone;
    }

    @JsonProperty("id")
    public String id() {
        return id;
    }

    @JsonProperty("enabled")
    public boolean enabled() {
        return enabled;
    }

    @JsonProperty("projectName")
    public String projectName() {
        return projectName;
    }

    @Nullable
    @JsonProperty("schedule")
    public String schedule() {
        return schedule;
    }

    @JsonProperty("direction")
    public String direction() {
        return direction;
    }

    // TODO(minwoox): Remove this property after migration is done.
    @JsonProperty("localRepo")
    public String localRepo() {
        return localRepo;
    }

    @JsonProperty("localPath")
    public String localPath() {
        return localPath;
    }

    @JsonProperty("remoteScheme")
    public String remoteScheme() {
        return remoteScheme;
    }

    @JsonProperty("remoteUrl")
    public String remoteUrl() {
        return remoteUrl;
    }

    @JsonProperty("remotePath")
    public String remotePath() {
        return remotePath;
    }

    @JsonProperty("remoteBranch")
    public String remoteBranch() {
        return remoteBranch;
    }

    @Nullable
    @JsonProperty("gitignore")
    public String gitignore() {
        return gitignore;
    }

    @JsonProperty("credentialResourceName")
    public String credentialResourceName() {
        return credentialResourceName;
    }

    @Nullable
    @JsonProperty("zone")
    public String zone() {
        return zone;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MirrorRequest)) {
            return false;
        }
        final MirrorRequest mirrorRequest = (MirrorRequest) o;
        return id.equals(mirrorRequest.id) &&
               enabled == mirrorRequest.enabled &&
               projectName.equals(mirrorRequest.projectName) &&
               Objects.equals(schedule, mirrorRequest.schedule) &&
               direction.equals(mirrorRequest.direction) &&
               localRepo.equals(mirrorRequest.localRepo) &&
               localPath.equals(mirrorRequest.localPath) &&
               remoteScheme.equals(mirrorRequest.remoteScheme) &&
               remoteUrl.equals(mirrorRequest.remoteUrl) &&
               remotePath.equals(mirrorRequest.remotePath) &&
               remoteBranch.equals(mirrorRequest.remoteBranch) &&
               Objects.equals(gitignore, mirrorRequest.gitignore) &&
               credentialResourceName.equals(mirrorRequest.credentialResourceName) &&
               Objects.equals(zone, mirrorRequest.zone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, projectName, schedule, direction, localRepo, localPath, remoteScheme, remoteUrl,
                            remotePath, remoteBranch, gitignore, credentialResourceName, enabled, zone);
    }

    protected ToStringHelper toStringHelper() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("id", id)
                          .add("enabled", enabled)
                          .add("projectName", projectName)
                          .add("schedule", schedule)
                          .add("direction", direction)
                          .add("localRepo", localRepo)
                          .add("localPath", localPath)
                          .add("remoteScheme", remoteScheme)
                          .add("remoteUrl", remoteUrl)
                          .add("remotePath", remotePath)
                          .add("remoteBranch", remoteBranch)
                          .add("gitignore", gitignore)
                          .add("credentialResourceName", credentialResourceName)
                          .add("zone", zone);
    }

    @Override
    public String toString() {
        return toStringHelper().toString();
    }
}
