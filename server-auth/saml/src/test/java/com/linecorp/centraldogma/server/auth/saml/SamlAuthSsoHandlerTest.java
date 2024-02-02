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

import static com.linecorp.centraldogma.server.auth.saml.HtmlUtil.getHtmlWithOnload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Subject;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;

class SamlAuthSsoHandlerTest {

    @Test
    void relayStateIsHtmlEscaped() {
        final SamlAuthSsoHandler samlAuthSsoHandler =
                new SamlAuthSsoHandler(() -> "id", session -> CompletableFuture.completedFuture(null),
                                       Duration.ofDays(1), name -> "foo", "foo", null);

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

        final MessageContext<Response> messageContext = new MessageContext<>();
        messageContext.setMessage(response);
        final String relayState = "'.substr(0.1)'\"&<>";
        final HttpResponse httpResponse =
                samlAuthSsoHandler.loginSucceeded(ctx, req, messageContext, null, relayState);
        assertThat(httpResponse.aggregate().join().contentUtf8()).isEqualTo(getHtmlWithOnload(
                "localStorage.setItem('sessionId','id')",
                "window.location.href='/#&#39;.substr(0.1)&#39;&quot;&amp;&lt;&gt;'"));
    }
}
