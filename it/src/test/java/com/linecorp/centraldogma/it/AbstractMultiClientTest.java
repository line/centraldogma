/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.it;

import static com.google.common.base.Preconditions.checkState;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.EnumSet;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

@RunWith(Parameterized.class)
public abstract class AbstractMultiClientTest {

    @Parameters(name = "{index}: {0}")
    public static Collection<ClientType> clientTypes() {
        return EnumSet.allOf(ClientType.class);
    }

    private final ClientType clientType;

    AbstractMultiClientTest(ClientType clientType) {
        this.clientType = clientType;
    }

    final CentralDogma client() {
        CentralDogmaRule rule = null;
        for (Field f : getClass().getDeclaredFields()) {
            if (CentralDogmaRule.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                try {
                    rule = (CentralDogmaRule) ((f.getModifiers() & Modifier.STATIC) != 0 ? f.get(null)
                                                                                         : f.get(this));
                } catch (Exception ignored) {
                    // Failed to get the field.
                }
                break;
            }
        }

        checkState(rule != null, "could not find a CentralDogmaRule field");
        return clientType.client(rule);
    }

    final ClientType clientType() {
        return clientType;
    }
}
