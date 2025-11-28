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

import static com.linecorp.centraldogma.internal.Util.APPLICATION_EMAIL_SUFFIX;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

/**
 * A {@link User} which accesses the API with an {@link Application}.
 */
public final class UserWithApplication extends User {

    private static final long serialVersionUID = 6021146546653491444L;

    private final Application application;

    /**
     * Creates a new instance.
     */
    public UserWithApplication(Application application) {
        super(requireNonNull(application, "application").appId(),
              application.appId() + APPLICATION_EMAIL_SUFFIX);
        this.application = application;
    }

    /**
     * Returns the {@link Application} of the user.
     */
    public Application application() {
        return application;
    }

    @Override
    public boolean isSystemAdmin() {
        return application.isSystemAdmin();
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + application.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserWithApplication)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        final UserWithApplication that = (UserWithApplication) o;
        return application.equals(that.application);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("application", application)
                          .toString();
    }
}
