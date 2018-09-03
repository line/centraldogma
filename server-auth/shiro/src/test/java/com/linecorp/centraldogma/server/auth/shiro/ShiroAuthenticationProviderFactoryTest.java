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
package com.linecorp.centraldogma.server.auth.shiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Iterator;
import java.util.ServiceLoader;

import org.junit.Test;

import com.linecorp.centraldogma.server.auth.AuthenticationProviderFactory;

public class ShiroAuthenticationProviderFactoryTest {

    @Test
    public void shouldBeLoaded() {
        final ServiceLoader<AuthenticationProviderFactory> loader =
                ServiceLoader.load(AuthenticationProviderFactory.class,
                                   ShiroAuthenticationProviderFactoryTest.class.getClassLoader());
        assertThat(loader).isNotNull();

        final Iterator<AuthenticationProviderFactory> it = loader.iterator();
        assertThat(it.hasNext()).isTrue();
        assertThat(it.next()).isInstanceOf(ShiroAuthenticationProviderFactory.class);
        assertThat(it.hasNext()).isFalse();
    }
}
