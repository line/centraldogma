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

package com.linecorp.centraldogma.server.mirror;

import java.net.URI;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.linecorp.centraldogma.server.internal.mirror.credential.AccessTokenMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.NoneMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredential;

/**
 * The authentication credentials which are required when accessing the Git repositories.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
        @Type(value = NoneMirrorCredential.class, name = "none"),
        @Type(value = PasswordMirrorCredential.class, name = "password"),
        @Type(value = PublicKeyMirrorCredential.class, name = "public_key"),
        @Type(value = AccessTokenMirrorCredential.class, name = "access_token")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public interface MirrorCredential {

    MirrorCredential FALLBACK =
            new NoneMirrorCredential("", true, Collections.singleton(Pattern.compile("^.*$")));

    /**
     * Returns the ID of the credential.
     */
    @JsonProperty("id")
    String id();

    /**
     * Returns the {@link Pattern}s compiled from the regular expressions that match a host name.
     */
    @JsonProperty("hostnamePatterns")
    Set<Pattern> hostnamePatterns();

    /**
     * Returns whether this {@link MirrorCredential} is enabled.
     */
    @JsonProperty("enabled")
    boolean enabled();

    /**
     * Returns {@code true} if the specified {@code uri} is matched by one of the host name patterns.
     *
     * @param uri a URI of a Git repository
     */
    boolean matches(URI uri);
}
