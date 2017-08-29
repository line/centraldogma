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
package com.linecorp.centraldogma.server;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

final class CacheStatsSerializer extends StdSerializer<CacheStats> {

    private static final long serialVersionUID = 4356076669993625289L;

    CacheStatsSerializer() {
        super(CacheStats.class);
    }

    @Override
    public void serialize(CacheStats value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        gen.writeNumberField("requestCount", value.requestCount());
        gen.writeNumberField("hitCount", value.hitCount());
        gen.writeNumberField("hitRate", value.hitRate());
        gen.writeNumberField("missCount", value.missCount());
        gen.writeNumberField("missRate", value.missRate());
        gen.writeNumberField("loadCount", value.loadCount());
        gen.writeNumberField("loadSuccessCount", value.loadSuccessCount());
        gen.writeNumberField("loadFailureCount", value.loadFailureCount());
        gen.writeNumberField("loadFailureRate", value.loadFailureRate());
        gen.writeNumberField("totalLoadTime", value.totalLoadTime());
        gen.writeNumberField("averageLoadPenalty", value.averageLoadPenalty());
        gen.writeNumberField("evictionCount", value.evictionCount());
        gen.writeNumberField("evictionWeight", value.evictionWeight());
        gen.writeEndObject();
    }
}
