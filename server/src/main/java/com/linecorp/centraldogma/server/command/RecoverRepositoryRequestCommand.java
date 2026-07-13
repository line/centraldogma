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

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;

/**
 * A {@link Command} which asks the source replica to originate a {@link RecoverRepositoryCommand}. It is
 * originated by a non-source replica that received the recovery request (e.g. behind a load balancer) and is
 * applied as a no-op on every replica; the source replica reacts to it (off the replication-log replay
 * thread) by building and originating the actual {@link RecoverRepositoryCommand}.
 */
public final class RecoverRepositoryRequestCommand extends RepositoryCommand<Void> {

    private final int sourceServerId;
    private final Revision fromRevision;

    @JsonCreator
    RecoverRepositoryRequestCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                                    @JsonProperty("author") @Nullable Author author,
                                    @JsonProperty("projectName") String projectName,
                                    @JsonProperty("repositoryName") String repositoryName,
                                    @JsonProperty("sourceServerId") int sourceServerId,
                                    @JsonProperty("fromRevision") Revision fromRevision) {
        super(CommandType.RECOVER_REPOSITORY_REQUEST, timestamp, author, projectName, repositoryName);
        this.sourceServerId = sourceServerId;
        this.fromRevision = requireNonNull(fromRevision, "fromRevision");
    }

    /**
     * Returns the ZooKeeper server ID of the source replica that should originate the recovery.
     */
    @JsonProperty
    public int sourceServerId() {
        return sourceServerId;
    }

    /**
     * Returns the first {@link Revision} to replay. Recovery replays {@code fromRevision..sourceHead}.
     */
    @JsonProperty
    public Revision fromRevision() {
        return fromRevision;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof RecoverRepositoryRequestCommand)) {
            return false;
        }
        final RecoverRepositoryRequestCommand that = (RecoverRepositoryRequestCommand) obj;
        return super.equals(that) &&
               sourceServerId == that.sourceServerId &&
               fromRevision.equals(that.fromRevision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceServerId, fromRevision) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("sourceServerId", sourceServerId)
                    .add("fromRevision", fromRevision);
    }
}
