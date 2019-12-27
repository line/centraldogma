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
package com.linecorp.centraldogma.server.auth.saml;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.auth.AuthProvider;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class SamlAuthTest {

    private static final SamlAuthProperties PROPERTIES;

    static {
        // The keys in 'test.jks' are generated from the following command:
        // $ keytool -genkey -keyalg RSA -sigalg SHA1withRSA -alias signing
        //      -keystore test.jks -storepass centraldogma
        try {
            PROPERTIES = Jackson.readValue(
                    '{' +
                    "\"entityId\": \"test-sp\"," +
                    "\"hostname\": \"dogma-example.linecorp.com\"," +
                    "\"keyStore\": {" +
                    "    \"type\": \"PKCS12\"," +
                    "    \"path\": \"test.jks\"," +
                    "    \"password\": \"centraldogma\"," +
                    "    \"keyPasswords\": {" +
                    "        \"signing\": \"centraldogma\"," +
                    "        \"encryption\": \"centraldogma\"" +
                    "    }" +
                    "}," +
                    "\"idp\": {" +
                    "    \"entityId\": \"test-idp\"," +
                    "    \"uri\": \"https://idp-example.linecorp.com/saml/sso\"" +
                    "}}", SamlAuthProperties.class);
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new SamlAuthProviderFactory());
            builder.authProviderProperties(PROPERTIES);
            builder.webAppEnabled(true);
        }
    };

    @Test
    void shouldUseBuiltinWebPageOnlyForLogout() {
        AggregatedHttpResponse resp;

        // Receive HTML which submits SAMLRequest to IdP.
        resp = dogma.httpClient().get(AuthProvider.LOGIN_PATH).aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
        assertThat(resp.headers().contentType()).isEqualTo(MediaType.HTML_UTF_8);
        assertThat(resp.contentUtf8()).contains("<input type=\"hidden\" name=\"SAMLRequest\"");

        // Redirect to built-in web logout page.
        resp = dogma.httpClient().get(AuthProvider.LOGOUT_PATH).aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
        assertThat(resp.headers().get(HttpHeaderNames.LOCATION))
                .isEqualTo(AuthProvider.BUILTIN_WEB_LOGOUT_PATH);
    }

    @Test
    void shouldReturnMetadata() {
        final AggregatedHttpResponse resp = dogma.httpClient().get("/saml/metadata").aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
        assertThat(resp.headers().contentType()).isEqualTo(MediaType.parse("application/samlmetadata+xml"));
        assertThat(resp.headers().get(HttpHeaderNames.CONTENT_DISPOSITION))
                .contains("attachment; filename=\"saml_metadata.xml\"");

        // Check ACS URLs for the service provider.
        final int port = dogma.serverAddress().getPort();
        assertThat(resp.contentUtf8())
                .contains("entityID=\"test-sp\"")
                .contains("<md:AssertionConsumerService " +
                          "Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\" " +
                          "Location=\"http://dogma-example.linecorp.com:" + port + "/saml/acs/post")
                .contains("<md:AssertionConsumerService " +
                          "Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" " +
                          "Location=\"http://dogma-example.linecorp.com:" + port + "/saml/acs/redirect");
    }
}
