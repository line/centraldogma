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

package com.linecorp.centraldogma.server.admin_v2.dto;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.internal.thrift.Revision;

public class RevisionDto {
    private final int major;
    private final int minor;
    private final String revisionNumber;

    public RevisionDto(Revision revision) {
        requireNonNull(revision, "revision");

        major = revision.getMajor();
        minor = revision.getMinor();
        revisionNumber = String.format("%d.%d", major, minor);
    }

    public RevisionDto(int major, int minor, String revisionNumber) {
        this.major = major;
        this.minor = minor;
        this.revisionNumber = requireNonNull(revisionNumber, "revisionNumber");
    }

    public Integer getMajor() {
        return major;
    }

    public Integer getMinor() {
        return minor;
    }

    public String getRevisionNumber() {
        return revisionNumber;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("major", major)
                          .add("minor", minor)
                          .toString();
    }
}
