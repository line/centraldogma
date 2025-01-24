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

package com.linecorp.centraldogma.server.internal.credential;

import static com.linecorp.centraldogma.internal.CredentialUtil.PROJECT_CREDENTIAL_ID_PATTERN;
import static com.linecorp.centraldogma.internal.CredentialUtil.REPO_CREDENTIAL_ID_PATTERN;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Objects;

import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.credential.CredentialType;

abstract class AbstractCredential implements Credential {

    private final String name;
    private final String id;
    private final CredentialType type;

    AbstractCredential(String name, CredentialType type) {
        this.name = requireNonNull(name, "name");
        id = extractId(name, type);
        this.type = requireNonNull(type, "type");
    }

    private static String extractId(String name, CredentialType type) {
        if (type == CredentialType.NONE && name.isEmpty()) {
            // The name of the NONE credential can be empty.
            return "";
        }
        // Credential name is validated when creating in CredentialService so just extract the id.
        final int lastIndex = name.lastIndexOf('/');
        if (lastIndex < 0) {
            throw new IllegalArgumentException("name: " + name +
                                               " (expected: " + PROJECT_CREDENTIAL_ID_PATTERN.pattern() +
                                               " or " + REPO_CREDENTIAL_ID_PATTERN.pattern() + ')');
        }
        return name.substring(lastIndex + 1);
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public final CredentialType type() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final AbstractCredential that = (AbstractCredential) o;
        return name.equals(that.name) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name, type);
    }

    @Override
    public final String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this);
        helper.add("name", name);
        helper.add("type", type);
        addProperties(helper);
        return helper.toString();
    }

    abstract void addProperties(ToStringHelper helper);
}
