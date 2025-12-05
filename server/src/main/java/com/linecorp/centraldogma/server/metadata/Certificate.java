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

package com.linecorp.centraldogma.server.metadata;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Objects;

/**
 * Specifies details of a certificate-based application identity.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public final class Certificate extends AbstractApplication {

    private final String certificateId;

    Certificate(String appId, String certificateId, boolean isSystemAdmin, boolean allowGuestAccess,
                UserAndTimestamp creation) {
        this(appId, certificateId, isSystemAdmin, allowGuestAccess, creation, null, null);
    }

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public Certificate(@JsonProperty("appId") String appId,
                       @JsonProperty("certificateId") String certificateId,
                       @JsonProperty("systemAdmin") boolean isSystemAdmin,
                       @JsonProperty("allowGuestAccess") @Nullable Boolean allowGuestAccess,
                       @JsonProperty("creation") UserAndTimestamp creation,
                       @JsonProperty("deactivation") @Nullable UserAndTimestamp deactivation,
                       @JsonProperty("deletion") @Nullable UserAndTimestamp deletion) {
        super(appId, ApplicationType.CERTIFICATE, isSystemAdmin,
              firstNonNull(allowGuestAccess, false), // Disallow guest access by default for certificate.
              requireNonNull(creation, "creation"), deactivation, deletion);
        this.certificateId = requireNonNull(certificateId, "certificateId");
    }

    /**
     * Returns the ID of the certificate.
     */
    @JsonProperty
    public String certificateId() {
        return certificateId;
    }

    @Override
    public Certificate withSystemAdmin(boolean isSystemAdmin) {
        if (isSystemAdmin == isSystemAdmin()) {
            return this;
        }
        return new Certificate(appId(), certificateId, isSystemAdmin, allowGuestAccess(),
                               creation(), deactivation(), deletion());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), certificateId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Certificate)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final Certificate that = (Certificate) o;
        return Objects.equal(certificateId, that.certificateId);
    }

    @Override
    void addProperties(ToStringHelper helper) {
        helper.add("certificateId", certificateId);
    }
}
