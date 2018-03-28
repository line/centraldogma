/*
 * Copyright 2018 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class CentralDogmaExceptionTest {
    @Test
    public void tracelessInstantiation() {
        final AtomicBoolean filledInStackTrace = new AtomicBoolean();
        final CentralDogmaException e = new CentralDogmaException("foo", false) {
            private static final long serialVersionUID = -4128575947135273677L;

            @Override
            public synchronized Throwable fillInStackTrace() {
                filledInStackTrace.set(true);
                return super.fillInStackTrace();
            }
        };
        assertThat(e).hasMessage("foo");
        assertThat(e.getStackTrace()).isEmpty();
        assertThat(filledInStackTrace).isFalse();
    }
}
