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
package com.linecorp.centraldogma.server.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Base64;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * Serializes a raw session instance into a base64-encoded string.
 */
public final class RawSessionJsonSerializer extends StdSerializer<Serializable> {

    private static final long serialVersionUID = -8209099521255193022L;

    /**
     * Creates a new instance.
     */
    public RawSessionJsonSerializer() {
        super(Serializable.class);
    }

    @Override
    public void serialize(Serializable value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(value);
            oos.flush();
            gen.writeString(Base64.getEncoder().encodeToString(baos.toByteArray()));
        }
    }
}
