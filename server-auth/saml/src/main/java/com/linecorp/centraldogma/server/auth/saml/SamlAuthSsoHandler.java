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
package com.linecorp.centraldogma.server.auth.saml;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.server.auth.saml.HtmlUtil.getHtmlWithCsrfAndRedirect;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.createSessionIdCookie;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.messaging.context.SAMLBindingContext;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.core.Response;
import org.owasp.encoder.Encode;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.saml.InvalidSamlRequestException;
import com.linecorp.armeria.server.saml.SamlBindingProtocol;
import com.linecorp.armeria.server.saml.SamlIdentityProviderConfig;
import com.linecorp.armeria.server.saml.SamlSingleSignOnHandler;
import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * A {@link SamlSingleSignOnHandler} implementation for the Central Dogma server.
 */
final class SamlAuthSsoHandler implements SamlSingleSignOnHandler {

    private final Supplier<String> sessionIdGenerator;
    private final Function<Session, CompletableFuture<Void>> loginSessionPropagator;
    private final Duration sessionValidDuration;
    private final long cookieMaxAgeSecond;
    private final Function<String, String> loginNameNormalizer;
    @Nullable
    private final String subjectLoginNameIdFormat;
    @Nullable
    private final String attributeLoginName;
    private final boolean tlsEnabled;

    SamlAuthSsoHandler(
            Supplier<String> sessionIdGenerator,
            Function<Session, CompletableFuture<Void>> loginSessionPropagator,
            Duration sessionValidDuration, Function<String, String> loginNameNormalizer,
            @Nullable String subjectLoginNameIdFormat, @Nullable String attributeLoginName,
            boolean tlsEnabled) {
        this.sessionIdGenerator = requireNonNull(sessionIdGenerator, "sessionIdGenerator");
        this.loginSessionPropagator = requireNonNull(loginSessionPropagator, "loginSessionPropagator");
        this.sessionValidDuration = requireNonNull(sessionValidDuration, "sessionValidDuration");
        // Make the cookie expire a bit earlier than the session itself.
        cookieMaxAgeSecond = sessionValidDuration.minusMinutes(1).getSeconds();
        this.loginNameNormalizer = requireNonNull(loginNameNormalizer, "loginNameNormalizer");
        checkArgument(!Strings.isNullOrEmpty(subjectLoginNameIdFormat) ||
                      !Strings.isNullOrEmpty(attributeLoginName),
                      "a name ID format of a subject or an attribute name should be specified " +
                      "for finding a login name");
        this.subjectLoginNameIdFormat = subjectLoginNameIdFormat;
        this.attributeLoginName = attributeLoginName;
        this.tlsEnabled = tlsEnabled;
    }

    @Override
    public CompletionStage<Void> beforeInitiatingSso(ServiceRequestContext ctx, HttpRequest req,
                                                     MessageContext<AuthnRequest> message,
                                                     SamlIdentityProviderConfig idpConfig) {
        final QueryStringDecoder decoder = new QueryStringDecoder(req.path(), true);
        final List<String> ref = decoder.parameters().get("ref");
        if (ref == null || ref.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        final String relayState = ref.get(0);
        if (idpConfig.ssoEndpoint().bindingProtocol() == SamlBindingProtocol.HTTP_REDIRECT &&
            relayState.length() > 80) {
            return CompletableFuture.completedFuture(null);
        }

        final SAMLBindingContext sub = message.getSubcontext(SAMLBindingContext.class, true);
        assert sub != null : SAMLBindingContext.class.getName();
        sub.setRelayState(relayState);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public HttpResponse loginSucceeded(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                       MessageContext<Response> message, @Nullable String sessionIndex,
                                       @Nullable String relayState) {
        final Response response = requireNonNull(message, "message").getMessage();
        final String username = Optional.ofNullable(findLoginNameFromSubjects(response))
                                        .orElseGet(() -> findLoginNameFromAttributes(response));
        if (Strings.isNullOrEmpty(username)) {
            return loginFailed(ctx, req, message,
                               new IllegalStateException("Cannot get a username from the response"));
        }

        final String sessionId = sessionIdGenerator.get();
        // Call once more to generate a CSRF token.
        final String csrfToken = sessionIdGenerator.get();
        final Session session =
                new Session(sessionId, csrfToken, loginNameNormalizer.apply(username), sessionValidDuration);

        final String redirectionScript;
        if (!Strings.isNullOrEmpty(relayState)) {
            final String trimmed = relayState.trim();
            if (!trimmed.startsWith("/") || trimmed.startsWith("//")) {
                redirectionScript = "window.location.href='/'";
            } else {
                redirectionScript = "window.location.href='" + Encode.forJavaScript(trimmed) + '\'';
            }
        } else {
            redirectionScript = "window.location.href='/'";
        }
        return HttpResponse.of(loginSessionPropagator.apply(session).thenApply(
                unused -> {
                    final Cookie cookie = createSessionIdCookie(sessionId, tlsEnabled, cookieMaxAgeSecond);
                    final ResponseHeaders responseHeaders =
                            ResponseHeaders.builder(HttpStatus.OK)
                                           .contentType(MediaType.HTML_UTF_8)
                                           .cookie(cookie).build();
                    return HttpResponse.of(responseHeaders,
                                           HttpData.ofUtf8(
                                                   getHtmlWithCsrfAndRedirect(csrfToken, redirectionScript)));
                }));
    }

    @Nullable
    private String findLoginNameFromSubjects(Response response) {
        if (Strings.isNullOrEmpty(subjectLoginNameIdFormat)) {
            return null;
        }
        return response.getAssertions()
                       .stream()
                       .map(s -> s.getSubject().getNameID())
                       .filter(nameId -> nameId.getFormat().equals(subjectLoginNameIdFormat))
                       .map(NameIDType::getValue)
                       .findFirst()
                       .orElse(null);
    }

    @Nullable
    private String findLoginNameFromAttributes(Response response) {
        if (Strings.isNullOrEmpty(attributeLoginName)) {
            return null;
        }
        return response.getAssertions()
                       .stream()
                       .flatMap(s -> s.getAttributeStatements().stream())
                       .flatMap(s -> s.getAttributes().stream())
                       .filter(attr -> attr.getName().equals(attributeLoginName))
                       .findFirst()
                       .map(attr -> {
                           final XMLObject v = attr.getAttributeValues().get(0);
                           if (v instanceof XSString) {
                               return ((XSString) v).getValue();
                           } else {
                               return null;
                           }
                       })
                       .orElse(null);
    }

    @Override
    public HttpResponse loginFailed(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                    @Nullable MessageContext<Response> message, Throwable cause) {
        final HttpStatus status =
                cause instanceof InvalidSamlRequestException ? HttpStatus.BAD_REQUEST
                                                             : HttpStatus.INTERNAL_SERVER_ERROR;
        return HttpApiUtil.newResponse(ctx, status, cause);
    }
}
