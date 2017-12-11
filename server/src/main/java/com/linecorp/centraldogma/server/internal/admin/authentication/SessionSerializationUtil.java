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

package com.linecorp.centraldogma.server.internal.admin.authentication;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;

import org.apache.shiro.session.mgt.SimpleSession;

/**
 * A utility for (de)serialization of {@link SimpleSession}.
 */
final class SessionSerializationUtil {

    /**
     * Returns a base64 encoded string of a serialized {@link SimpleSession} object.
     */
    static String serialize(SimpleSession session) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(session);
            oos.flush();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Session serialization failure", e);
        }
    }

    /**
     * Returns a {@link SimpleSession} deserialized from a base64 encoded string.
     */
    static SimpleSession deserialize(String base64Encoded) {
        try (ByteArrayInputStream bais =
                     new ByteArrayInputStream(Base64.getDecoder().decode(base64Encoded.trim()));
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (SimpleSession) ois.readObject();
        } catch (Exception e) {
            throw new RuntimeException("Session deserialization failure", e);
        }
    }

    private SessionSerializationUtil() {}
}
