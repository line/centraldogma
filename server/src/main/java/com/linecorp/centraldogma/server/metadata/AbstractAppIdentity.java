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

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Objects;

import com.linecorp.centraldogma.internal.Util;

/**
 * An abstract base class for {@link AppIdentity} implementations.
 */
abstract class AbstractAppIdentity implements AppIdentity {

    private final String appId;
    private final AppIdentityType type;
    private final boolean isSystemAdmin;
    private final boolean allowGuestAccess;
    private final UserAndTimestamp creation;
    @Nullable
    private final UserAndTimestamp deactivation;
    @Nullable
    private final UserAndTimestamp deletion;

    AbstractAppIdentity(String appId, AppIdentityType type, boolean isSystemAdmin,
                        boolean allowGuestAccess, UserAndTimestamp creation,
                        @Nullable UserAndTimestamp deactivation, @Nullable UserAndTimestamp deletion) {
        this.appId = Util.validateFileName(appId, "appId");
        this.type = requireNonNull(type, "type");
        this.isSystemAdmin = isSystemAdmin;
        this.allowGuestAccess = allowGuestAccess;
        this.creation = requireNonNull(creation, "creation");
        this.deactivation = deactivation;
        this.deletion = deletion;
    }

    @Override
    public String id() {
        return appId;
    }

    @Override
    public String appId() {
        return appId;
    }

    @Override
    public AppIdentityType type() {
        return type;
    }

    @Override
    public boolean isSystemAdmin() {
        return isSystemAdmin;
    }

    @Override
    public boolean allowGuestAccess() {
        return allowGuestAccess;
    }

    @Override
    public UserAndTimestamp creation() {
        return creation;
    }

    @Nullable
    @Override
    public UserAndTimestamp deactivation() {
        return deactivation;
    }

    @Nullable
    @Override
    public UserAndTimestamp deletion() {
        return deletion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final AbstractAppIdentity that = (AbstractAppIdentity) o;
        return appId.equals(that.appId) &&
               type == that.type &&
               isSystemAdmin == that.isSystemAdmin &&
               allowGuestAccess == that.allowGuestAccess &&
               creation.equals(that.creation) &&
               Objects.equal(deactivation, that.deactivation) &&
               Objects.equal(deletion, that.deletion);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(appId, type, isSystemAdmin, allowGuestAccess, creation, deactivation, deletion);
    }

    @Override
    public final String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this).omitNullValues()
                                                 .add("appId", id())
                                                 .add("type", type())
                                                 .add("isSystemAdmin", isSystemAdmin())
                                                 .add("allowGuestAccess", allowGuestAccess())
                                                 .add("creation", creation())
                                                 .add("deactivation", deactivation())
                                                 .add("deletion", deletion());
        addProperties(helper);
        return helper.toString();
    }

    abstract void addProperties(ToStringHelper helper);
}
