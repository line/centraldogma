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
package com.linecorp.centraldogma.server.support.shiro;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Base64;

import org.apache.shiro.session.mgt.SimpleSession;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Deserialized a {@link SimpleSession} from a base64-encoded string.
 */
public final class SimpleSessionJsonDeserializer extends StdDeserializer<SimpleSession> {

    private static final long serialVersionUID = 6711539370106208875L;

    public SimpleSessionJsonDeserializer() {
        super(SimpleSession.class);
    }

    @Override
    public SimpleSession deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        try (ByteArrayInputStream bais =
                     new ByteArrayInputStream(Base64.getDecoder().decode(p.readValueAs(String.class)));
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (SimpleSession) ois.readObject();
        } catch (ClassNotFoundException e) {
            ctxt.reportInputMismatch(SimpleSession.class, "failed to deserialize a session: " + e);
            throw new Error(); // Should never reach here
        }
    }
}
