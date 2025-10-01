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
package com.linecorp.centraldogma.server.auth.saml;

import static com.linecorp.centraldogma.server.auth.saml.HtmlUtil.getHtmlWithCsrfAndRedirect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Subject;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.storage.encryption.NoopEncryptionStorageManager;

class SamlAuthSsoHandlerTest {

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void relayStateIsHtmlEscaped(boolean tlsEnabled) {
        final AtomicInteger counter = new AtomicInteger();
        final Supplier<String> sessionIdGenerator = () -> Integer.toString(counter.incrementAndGet());
        final SamlAuthSsoHandler samlAuthSsoHandler =
                new SamlAuthSsoHandler(sessionIdGenerator, session -> CompletableFuture.completedFuture(null),
                                       () -> true, Duration.ofDays(1), name -> "foo", "foo", null, tlsEnabled,
                                       NoopEncryptionStorageManager.INSTANCE);

        final AggregatedHttpRequest req = AggregatedHttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx = ServiceRequestContext.of(req.toHttpRequest());

        final NameID nameId = mock(NameID.class);
        when(nameId.getFormat()).thenReturn("foo");
        when(nameId.getValue()).thenReturn("foo");
        final Subject subject = mock(Subject.class);
        when(subject.getNameID()).thenReturn(nameId);
        final Assertion assertion = mock(Assertion.class);
        when(assertion.getSubject()).thenReturn(subject);
        final Response response = mock(Response.class);
        when(response.getAssertions()).thenReturn(ImmutableList.of(assertion));

        MessageContext<Response> messageContext = new MessageContext<>();
        messageContext.setMessage(response);
        String relayState = "/'.substr(0.1)'\"&<>";
        HttpResponse httpResponse =
                samlAuthSsoHandler.loginSucceeded(ctx, req, messageContext, null, relayState);
        AggregatedHttpResponse aggregated = httpResponse.aggregate().join();
        assertThat(aggregated.contentUtf8()).isEqualTo(getHtmlWithCsrfAndRedirect(
                "2",
                "window.location.href='\\/\\x27.substr(0.1)\\x27\\x22\\x26<>'"));
        assertCookie(tlsEnabled, aggregated.headers(), "1");

        messageContext = new MessageContext<>();
        messageContext.setMessage(response);
        // Does not start with '/'.
        relayState = "'.substr(0.1)'\"&<>";
        httpResponse = samlAuthSsoHandler.loginSucceeded(ctx, req, messageContext, null, relayState);
        aggregated = httpResponse.aggregate().join();
        assertThat(aggregated.contentUtf8()).isEqualTo(getHtmlWithCsrfAndRedirect(
                "4", "window.location.href='/'"));
        assertCookie(tlsEnabled, aggregated.headers(), "3");
    }

    private static void assertCookie(boolean tlsEnabled, ResponseHeaders responseHeaders, String value) {
        final String setCookieValue = responseHeaders.get(HttpHeaderNames.SET_COOKIE);
        final Cookie setCookie = Cookie.fromSetCookieHeader(setCookieValue);
        assertThat(setCookie).isNotNull();
        if (tlsEnabled) {
            assertThat(setCookie.name()).isEqualTo("__Host-Http-session-id");
            assertThat(setCookie.isSecure()).isTrue();
        } else {
            assertThat(setCookie.name()).isEqualTo("session-id");
            assertThat(setCookie.isSecure()).isFalse();
        }
        assertThat(setCookie.value()).isEqualTo(value);
        assertThat(setCookie.maxAge()).isEqualTo(86340);
        assertThat(setCookie.isHttpOnly()).isTrue();
        assertThat(setCookie.path()).isEqualTo("/");
        assertThat(setCookie.sameSite()).isEqualTo("Strict");
    }
}
