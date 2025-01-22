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

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(Include.NON_NULL)
public final class MirrorDto extends MirrorRequest {

    private final boolean allow;

    @JsonCreator
    public MirrorDto(@JsonProperty("id") String id,
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
                     @JsonProperty("zone") @Nullable String zone,
                     @JsonProperty("allow") boolean allow) {
        super(id, enabled, projectName, schedule, direction, localRepo, localPath, remoteScheme, remoteUrl,
              remotePath, remoteBranch, gitignore, credentialId, credentialResourceName, zone);
        this.allow = allow;
    }

    @JsonProperty("allow")
    public boolean allow() {
        return allow;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MirrorDto)) {
            return false;
        }
        final MirrorDto mirrorDto = (MirrorDto) o;
        return super.equals(o) && allow == mirrorDto.allow;
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + Objects.hash(allow);
    }

    @Override
    public String toString() {
        return toStringHelper().add("allow", allow).toString();
    }
}
