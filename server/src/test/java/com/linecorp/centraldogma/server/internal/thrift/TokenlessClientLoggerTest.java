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
package com.linecorp.centraldogma.server.internal.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.NetUtil;

@RunWith(MockitoJUnitRunner.class)
public class TokenlessClientLoggerTest {

    @Mock
    private Clock clock; // = Mockito.mock(Clock.class);
    @SuppressWarnings("unchecked")
    @Mock
    private Service<HttpRequest, HttpResponse> delegate; // = Mockito.mock(Service.class);

    @Test
    public void testWithToken() throws Exception {
        final MockTokenlessClientLogger logger = new MockTokenlessClientLogger();

        // When a request with a CSRF token is received
        final ServiceRequestContext ctx = newContext("foo", "192.168.0.1");
        final HttpRequest req = newRequestWithToken();
        logger.serve(ctx, req);

        // hostname and IP address must not be reported.
        assertThat(logger.hostname).isNull();
        assertThat(logger.ip).isNull();
        // .. and the request must be delegated.
        verify(delegate, times(1)).serve(ctx, req);
        // .. and clock must not be queried.
        verify(clock, never()).instant();
    }

    @Test
    public void testWithoutToken() throws Exception {
        final MockTokenlessClientLogger logger = new MockTokenlessClientLogger();

        final Instant startTime = Instant.now();
        when(clock.instant()).thenReturn(startTime);

        // When a request without a CSRF token is received
        final ServiceRequestContext ctx = newContext("foo", "192.168.0.1");
        final HttpRequest req = newRequestWithoutToken();
        logger.serve(ctx, req);

        // hostname and IP address must be reported.
        assertThat(logger.hostname).isEqualTo("foo");
        assertThat(logger.ip).isEqualTo("192.168.0.1");

        // .. and the request must be delegated.
        verify(delegate, times(1)).serve(ctx, req);

        // When a request without a CSRF token is received again for the same IP address
        // (Use different hostname to make sure we check an IP only.)
        final ServiceRequestContext ctx2 = newContext("bar", "192.168.0.1");
        final HttpRequest req2 = newRequestWithoutToken();
        when(clock.instant()).thenReturn(startTime.plus(30, ChronoUnit.MINUTES));
        logger.serve(ctx2, req2);

        // hostname and IP address must not be reported.
        assertThat(logger.hostname).isNull();
        assertThat(logger.ip).isNull();
        // .. and the request must be delegated.
        verify(delegate, times(1)).serve(ctx2, req2);

        // When a request without a CSRF token is received again for the same IP address after a day
        final ServiceRequestContext ctx3 = newContext("baz", "192.168.0.1");
        final HttpRequest req3 = newRequestWithoutToken();
        when(clock.instant()).thenReturn(startTime.plus(1, ChronoUnit.DAYS));
        logger.serve(ctx3, req3);

        // hostname and IP address must be reported.
        assertThat(logger.hostname).isEqualTo("baz");
        assertThat(logger.ip).isEqualTo("192.168.0.1");
        // .. and the request must be delegated.
        verify(delegate, times(1)).serve(ctx3, req3);

        // Make sure a request from other remote address isn't affected.
        final ServiceRequestContext ctx4 = newContext("qux", "192.168.0.2");
        final HttpRequest req4 = newRequestWithoutToken();
        logger.serve(ctx4, req4);

        // hostname and IP address must be reported.
        assertThat(logger.hostname).isEqualTo("qux");
        assertThat(logger.ip).isEqualTo("192.168.0.2");
        // .. and the request must be delegated.
        verify(delegate, times(1)).serve(ctx4, req4);
    }

    private static ServiceRequestContext newContext(String hostname, String ip) {
        final ServiceRequestContext ctx = Mockito.mock(ServiceRequestContext.class);
        try {
            when(ctx.remoteAddress()).thenReturn(new InetSocketAddress(
                    InetAddress.getByAddress(hostname, NetUtil.createByteArrayFromIpAddressString(ip)),
                    ThreadLocalRandom.current().nextInt(32768, 65536)));
        } catch (UnknownHostException e) {
            throw new Error(e);
        }
        return ctx;
    }

    private static HttpRequest newRequestWithToken() {
        return HttpRequest.of(HttpHeaders.of(HttpMethod.GET, "/")
                                         .set(HttpHeaderNames.AUTHORIZATION, "bearer anonymous"));
    }

    private static HttpRequest newRequestWithoutToken() {
        return HttpRequest.of(HttpMethod.GET, "/");
    }

    private class MockTokenlessClientLogger extends TokenlessClientLogger {

        String hostname;
        String ip;

        MockTokenlessClientLogger() {
            super(delegate, clock);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            hostname = null;
            ip = null;
            return super.serve(ctx, req);
        }

        @Override
        void report(String hostname, String ip) {
            this.hostname = hostname;
            this.ip = ip;
        }
    }
}
