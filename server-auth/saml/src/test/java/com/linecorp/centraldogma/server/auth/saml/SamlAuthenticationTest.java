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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.auth.AuthenticationProvider;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class SamlAuthenticationTest {

    private static final SamlAuthenticationProperties PROPERTIES;

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
                    "}}", SamlAuthenticationProperties.class);
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    @ClassRule
    public static final CentralDogmaRule rule = new CentralDogmaRule() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new SamlAuthenticationProviderFactory());
            builder.authProviderProperties(PROPERTIES);
            builder.webAppEnabled(true);
        }
    };

    @Test
    public void shouldUseBuiltinWebPageOnlyForLogout() throws Exception {
        AggregatedHttpMessage resp;

        // Receive HTML which submits SAMLRequest to IdP.
        resp = rule.httpClient().get(AuthenticationProvider.LOGIN_PATH).aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
        assertThat(resp.headers().contentType()).isEqualTo(MediaType.HTML_UTF_8);
        assertThat(resp.content().toStringUtf8()).contains("<input type=\"hidden\" name=\"SAMLRequest\"");

        // Redirect to built-in web logout page.
        resp = rule.httpClient().get(AuthenticationProvider.LOGOUT_PATH).aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
        assertThat(resp.headers().get(HttpHeaderNames.LOCATION))
                .isEqualTo(AuthenticationProvider.BUILTIN_WEB_LOGOUT_PATH);
    }

    @Test
    public void shouldReturnMetadata() {
        final AggregatedHttpMessage resp = rule.httpClient().get("/saml/metadata").aggregate().join();
        assertThat(resp.status()).isEqualTo(HttpStatus.OK);
        assertThat(resp.headers().contentType()).isEqualTo(MediaType.parse("application/samlmetadata+xml"));
        assertThat(resp.headers().get(HttpHeaderNames.CONTENT_DISPOSITION))
                .contains("attachment; filename=\"saml_metadata.xml\"");

        // Check ACS URLs for the service provider.
        final int port = rule.serverAddress().getPort();
        assertThat(resp.content().toStringUtf8())
                .contains("entityID=\"test-sp\"")
                .contains("<md:AssertionConsumerService " +
                          "Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\" " +
                          "Location=\"http://dogma-example.linecorp.com:" + port + "/saml/acs/post")
                .contains("<md:AssertionConsumerService " +
                          "Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" " +
                          "Location=\"http://dogma-example.linecorp.com:" + port + "/saml/acs/redirect");
    }
}
