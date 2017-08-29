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

package com.linecorp.centraldogma.server.mirror.credential;

import java.net.URI;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @Type(value = NoneMirrorCredential.class, name = "none"),
        @Type(value = PasswordMirrorCredential.class, name = "password"),
        @Type(value = PublicKeyMirrorCredential.class, name = "public_key")
})
public interface MirrorCredential {

    MirrorCredential FALLBACK = new NoneMirrorCredential(Collections.singleton(Pattern.compile("^.*$")));

    Set<Pattern> hostnamePatterns();

    boolean matches(URI uri);
}
