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

package com.linecorp.centraldogma.server.command;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;

/**
 * A {@link Command} which is used for migrating a repository to an encrypted repository.
 */
public final class MigrateToEncryptedRepositoryCommand extends ProjectCommand<Void> {

    private final String repositoryName;
    private final byte[] wdek;

    @JsonCreator
    MigrateToEncryptedRepositoryCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                                        @JsonProperty("author") @Nullable Author author,
                                        @JsonProperty("projectName") String projectName,
                                        @JsonProperty("repositoryName") String repositoryName,
                                        @JsonProperty("wdek") byte[] wdek) {
        super(CommandType.MIGRATE_TO_ENCRYPTED_REPOSITORY, timestamp, author, projectName);
        this.repositoryName = requireNonNull(repositoryName, "repositoryName");
        this.wdek = requireNonNull(wdek, "wdek");
    }

    /**
     * Return the repository name.
     */
    @JsonProperty
    public String repositoryName() {
        return repositoryName;
    }

    /**
     * Returns the WDEK of the repository.
     */
    @JsonProperty
    public byte[] wdek() {
        return wdek.clone();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof MigrateToEncryptedRepositoryCommand)) {
            return false;
        }

        final MigrateToEncryptedRepositoryCommand that = (MigrateToEncryptedRepositoryCommand) obj;
        return super.equals(obj) &&
               repositoryName.equals(that.repositoryName) &&
               Arrays.equals(wdek, that.wdek);
    }

    @Override
    public int hashCode() {
        return ((repositoryName.hashCode() * 31) + wdek.hashCode()) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("repositoryName", repositoryName)
                    .add("wdek", "[***]");
    }
}
