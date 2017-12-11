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
package com.linecorp.centraldogma.common;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * Serializes a {@link Revision} into JSON.
 *
 * @see RevisionJsonDeserializer
 */
public class RevisionJsonSerializer extends StdSerializer<Revision> {

    private static final long serialVersionUID = 1536427117293976073L;

    public RevisionJsonSerializer() {
        super(Revision.class);
    }

    @Override
    public void serialize(Revision value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeNumber(value.major());
    }
}
