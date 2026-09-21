/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.centraldogma.server.command;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;

/**
 * A {@link Command} which asks the source replica to originate a {@link ApplyRepositoryRecoveryCommand}. It is
 * recorded by any replica that receives the recovery request and applied as a no-op on every replica; the
 * source replica reacts to it asynchronously by building and originating the actual
 * {@link ApplyRepositoryRecoveryCommand}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RequestRepositoryRecoveryCommand extends RepositoryCommand<Void> {

    private final int sourceServerId;
    private final Revision fromRevision;
    private final Revision toRevision;
    private final int maxRevision;

    @JsonCreator
    RequestRepositoryRecoveryCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                                    @JsonProperty("author") @Nullable Author author,
                                    @JsonProperty("projectName") String projectName,
                                    @JsonProperty("repositoryName") String repositoryName,
                                    @JsonProperty("sourceServerId") int sourceServerId,
                                    @JsonProperty("fromRevision") Revision fromRevision,
                                    @JsonProperty("toRevision") Revision toRevision,
                                    @JsonProperty(value = "maxRevision", required = true) int maxRevision) {
        super(CommandType.REQUEST_REPOSITORY_RECOVERY, timestamp, author, projectName, repositoryName);
        this.sourceServerId = sourceServerId;
        this.fromRevision = requireNonNull(fromRevision, "fromRevision");
        this.toRevision = requireNonNull(toRevision, "toRevision");
        checkArgument(maxRevision >= toRevision.major(),
                      "maxRevision: %s (expected: >= toRevision %s)", maxRevision, toRevision);
        checkArgument(maxRevision < Integer.MAX_VALUE,
                      "maxRevision: %s (expected: < %s)", maxRevision, Integer.MAX_VALUE);
        checkArgument((long) maxRevision - fromRevision.major() + 2 <=
                      ApplyRepositoryRecoveryCommand.MAX_RECOVERY_COMMITS,
                      "recovery spans too many revisions (maximum: %s)",
                      ApplyRepositoryRecoveryCommand.MAX_RECOVERY_COMMITS);
        this.maxRevision = maxRevision;
    }

    /**
     * Returns the ZooKeeper server ID of the source replica that should originate the recovery.
     */
    @JsonProperty("sourceServerId")
    public int sourceServerId() {
        return sourceServerId;
    }

    /**
     * Returns the first {@link Revision} to replay. Recovery replays {@code fromRevision..toRevision}.
     */
    @JsonProperty("fromRevision")
    public Revision fromRevision() {
        return fromRevision;
    }

    /**
     * Returns the last source {@link Revision} to replay before compatibility padding.
     */
    @JsonProperty("toRevision")
    public Revision toRevision() {
        return toRevision;
    }

    /**
     * Returns the greatest repository head observed across the replicas before the recovery started.
     */
    @JsonProperty("maxRevision")
    public int maxRevision() {
        return maxRevision;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof RequestRepositoryRecoveryCommand)) {
            return false;
        }
        final RequestRepositoryRecoveryCommand that = (RequestRepositoryRecoveryCommand) obj;
        return super.equals(that) &&
               sourceServerId == that.sourceServerId &&
               fromRevision.equals(that.fromRevision) &&
               toRevision.equals(that.toRevision) && maxRevision == that.maxRevision;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceServerId, fromRevision, toRevision, maxRevision) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("sourceServerId", sourceServerId)
                    .add("fromRevision", fromRevision)
                    .add("toRevision", toRevision)
                    .add("maxRevision", maxRevision);
    }
}
