/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.centraldogma.webapp;

import static com.google.common.base.Strings.isNullOrEmpty;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;

final class SamlIdpServer {

    private static final Logger logger = LoggerFactory.getLogger(SamlIdpServer.class);

    static Server newServer() {
        return Server.builder()
                     .http(8081)
                     .service("/sso/saml", new SamlIdpService())
                     .service("/login", new LoginService())
                     .build();
    }

    private static class SamlIdpService implements HttpService {

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(req.aggregate().thenApply(aggregatedHttpRequest -> {
                final QueryParams params = QueryParams.fromQueryString(
                        aggregatedHttpRequest.contentUtf8());
                final String encodedSamlRequest = params.get("SAMLRequest");
                if (isNullOrEmpty(encodedSamlRequest)) {
                    return HttpResponse.of(400);
                }

                logger.info("Received SAMLRequest: {}", encodedSamlRequest);
                final String relayState = params.get("RelayState");
                if (!isNullOrEmpty(relayState)) {
                    logger.info("Received RelayState: {}", relayState);
                }

                final String decodedSamlRequest = new String(Base64.getDecoder().decode(encodedSamlRequest),
                                                             StandardCharsets.UTF_8);
                logger.info("Decoded SAMLRequest: {}", decodedSamlRequest);
                final String loginForm = createLoginForm(decodedSamlRequest, relayState);
                return HttpResponse.of(HttpStatus.OK, MediaType.HTML_UTF_8, loginForm);
            }));
        }

        private static String createLoginForm(String samlRequest, String relayState) {
            return "<html>" +
                   "  <head><title>SAML IDP Login</title></head>" +
                   "  <body>" +
                   "    <h2>IDP Login</h2>" +
                   "    <form action='/login' method='post'>" +
                   "      <div>" +
                   "        <label for='username'>ID:</label>" +
                   "        <input type='text' id='username' name='username' value='foo'>" +
                   "      </div>" +
                   "      <div>" +
                   "        <label for='password'>Password:</label>" +
                   "        <input type='password' id='password' name='password' value='bar'>" +
                   "      </div>" +
                   "      <input type='hidden' name='SAMLRequest' value='" + samlRequest + "'/>" +
                   "      <input type='hidden' name='RelayState' value='" +
                   (relayState != null ? relayState : "") + "'/>" +
                   "      <br/>" +
                   "      <input type='submit' value='Login'>" +
                   "    </form>" +
                   "  </body>" +
                   "</html>";
        }
    }

    private static class LoginService implements HttpService {

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(req.aggregate().thenApply(aggregatedHttpRequest -> {
                final QueryParams params = QueryParams.fromQueryString(aggregatedHttpRequest.contentUtf8());
                final String username = params.get("username");
                final String password = params.get("password");
                final String samlRequest = params.get("SAMLRequest");
                final String relayState = params.get("RelayState");

                if (!("foo".equals(username) && "bar".equals(password))) {
                    final String loginFailedHtml =
                            "<html><body><h1>Login Failed</h1><p>Invalid username or password.</p>" +
                            "<a href='javascript:history.back()'>Go Back</a></body></html>";
                    return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.HTML_UTF_8, loginFailedHtml);
                }

                final String acsUrl;
                final String requestId;
                try {
                    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    final DocumentBuilder builder = factory.newDocumentBuilder();
                    final org.w3c.dom.Document parsed = builder.parse(
                            new InputSource(new StringReader(samlRequest)));
                    acsUrl = parsed.getDocumentElement().getAttribute("AssertionConsumerServiceURL");
                    requestId = parsed.getDocumentElement().getAttribute("ID");
                } catch (Exception e) {
                    logger.warn("Failed to parse SAMLRequest XML", e);
                    return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                                           "Failed to parse SAMLRequest XML.");
                }

                logger.info("Extracted ACS URL: {}, ID: {}", acsUrl, requestId);

                final String samlResponse = generateSamlResponse(username, requestId, acsUrl);
                final String encodedSamlResponse = Base64.getEncoder().encodeToString(
                        samlResponse.getBytes(StandardCharsets.UTF_8));

                final String autoPostForm = createAutoPostForm(acsUrl, encodedSamlResponse, relayState);
                return HttpResponse.of(HttpStatus.OK, MediaType.HTML_UTF_8, autoPostForm);
            }));
        }

        private static String generateSamlResponse(String username, String inResponseTo, String acsUrl) {
            final Instant issueInstant = Instant.now();
            final String assertionId = '_' + UUID.randomUUID().toString();
            final String responseId = '_' + UUID.randomUUID().toString();
            final String idpEntityId = "central-dogma-idp";
            final String spEntityId = "dogma";

            return "<saml2p:Response xmlns:saml2p=\"urn:oasis:names:tc:SAML:2.0:protocol\"" +
                   "                 Destination=\"" + acsUrl + '"' +
                   "                 ID=\"" + responseId + '"' +
                   "                 InResponseTo=\"" + inResponseTo + '"' +
                   "                 IssueInstant=\"" + issueInstant + '"' +
                   "                 Version=\"2.0\">" +
                   "    <saml2:Issuer xmlns:saml2=\"urn:oasis:names:tc:SAML:2.0:assertion\">" + idpEntityId +
                   "</saml2:Issuer>" +
                   "    <saml2p:Status>" +
                   "        <saml2p:StatusCode Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/>" +
                   "    </saml2p:Status>" +
                   "    <saml2:Assertion xmlns:saml2=\"urn:oasis:names:tc:SAML:2.0:assertion\"" +
                   "                     ID=\"" + assertionId + '"' +
                   "                     IssueInstant=\"" + issueInstant + '"' +
                   "                     Version=\"2.0\">" +
                   "        <saml2:Issuer>" + idpEntityId + "</saml2:Issuer>" +
                   "        <saml2:Subject>" +
                   "          <saml2:NameID Format=\"urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified\">" +
                   username + "</saml2:NameID>" +
                   "          <saml2:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\">" +
                   "              <saml2:SubjectConfirmationData NotOnOrAfter=\"" +
                   issueInstant.plusSeconds(300) + '"' +
                   "                                             Recipient=\"" + acsUrl + '"' +
                   "                                             InResponseTo=\"" + inResponseTo + "\"/>" +
                   "          </saml2:SubjectConfirmation>" +
                   "        </saml2:Subject>" +
                   "        <saml2:Conditions NotBefore=\"" + issueInstant.minusSeconds(60) + '"' +
                   "                          NotOnOrAfter=\"" + issueInstant.plusSeconds(300) + "\">" +
                   "            <saml2:AudienceRestriction>" +
                   "                <saml2:Audience>" + spEntityId + "</saml2:Audience>" +
                   "            </saml2:AudienceRestriction>" +
                   "        </saml2:Conditions>" +
                   "        <saml2:AuthnStatement AuthnInstant=\"" + issueInstant + "\">" +
                   "            <saml2:AuthnContext>" +
                   "                <saml2:AuthnContextClassRef>urn:oasis:names:tc:SAML:2.0:ac:classes:" +
                   "PasswordProtectedTransport</saml2:AuthnContextClassRef>" +
                   "            </saml2:AuthnContext>" +
                   "        </saml2:AuthnStatement>" +
                   "    </saml2:Assertion>" +
                   "</saml2p:Response>";
        }

        private static String createAutoPostForm(String acsUrl, String encodedSamlResponse, String relayState) {
            return "<html>" +
                   "    <body onload=\"document.forms[0].submit()\">" +
                   "        <noscript>" +
                   "            <p><strong>Note:</strong> Since your browser does not support JavaScript, " +
                   "you must press the Continue button once to proceed.</p>" +
                   "        </noscript>" +
                   "        <form method=\"post\" action=\"" + acsUrl + "\">" +
                   "            <input type=\"hidden\" name=\"SAMLResponse\" value=\"" + encodedSamlResponse +
                   "\"/>" +
                   "            <input type=\"hidden\" name=\"RelayState\" value=\"" +
                   (relayState != null ? relayState : "") + "\"/>" +
                   "            <noscript>" +
                   "                <input type=\"submit\" value=\"Continue\"/>" +
                   "            </noscript>" +
                   "        </form>" +
                   "    </body>" +
                   "</html>";
        }
    }

    private SamlIdpServer() {}
}
