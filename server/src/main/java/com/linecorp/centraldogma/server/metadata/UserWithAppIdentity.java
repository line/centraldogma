/*
 * Copyright 2019 LINE Corporation
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

import static com.linecorp.centraldogma.internal.Util.APP_IDENTITY_EMAIL_SUFFIX;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

/**
 * A {@link User} which accesses the API with an {@link AppIdentity}.
 */
public final class UserWithAppIdentity extends User {

    private static final long serialVersionUID = 6021146546653491444L;

    private final AppIdentity appIdentity;

    /**
     * Creates a new instance.
     */
    public UserWithAppIdentity(AppIdentity appIdentity) {
        super(requireNonNull(appIdentity, "appIdentity").appId(),
              appIdentity.appId() + APP_IDENTITY_EMAIL_SUFFIX);
        this.appIdentity = appIdentity;
    }

    /**
     * Returns the {@link AppIdentity} of the user.
     */
    public AppIdentity appIdentity() {
        return appIdentity;
    }

    @Override
    public boolean isSystemAdmin() {
        return appIdentity.isSystemAdmin();
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + appIdentity.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserWithAppIdentity)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        final UserWithAppIdentity that = (UserWithAppIdentity) o;
        return appIdentity.equals(that.appIdentity);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("appIdentity", appIdentity)
                          .toString();
    }
}
