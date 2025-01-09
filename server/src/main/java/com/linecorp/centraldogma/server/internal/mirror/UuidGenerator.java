/*
 * Copyright 2024 LINE Corporation
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
/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.centraldogma.server.internal.mirror;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Random;
import java.util.UUID;

/**
 * An {@link UuidGenerator} that uses {@link SecureRandom} for the initial seed and
 * {@link Random} thereafter, instead of calling {@link UUID#randomUUID()} every
 * time. This provides a better balance between securely random ids and performance.
 */
final class UuidGenerator {

    // Forked from https://github.com/spring-projects/spring-framework/blob/a2bc1ded73417332ac474f72f034f55f6f1e4ef6/spring-core/src/main/java/org/springframework/util/AlternativeJdkIdGenerator.java#L34

    private final Random random;

    UuidGenerator() {
        final SecureRandom secureRandom = new SecureRandom();
        final byte[] seed = new byte[8];
        secureRandom.nextBytes(seed);
        random = new Random(new BigInteger(seed).longValue());
    }

    UUID generateId() {
        final byte[] randomBytes = new byte[16];
        random.nextBytes(randomBytes);

        long mostSigBits = 0;
        for (int i = 0; i < 8; i++) {
            mostSigBits = (mostSigBits << 8) | (randomBytes[i] & 0xff);
        }

        long leastSigBits = 0;
        for (int i = 8; i < 16; i++) {
            leastSigBits = (leastSigBits << 8) | (randomBytes[i] & 0xff);
        }

        return new UUID(mostSigBits, leastSigBits);
    }
}
